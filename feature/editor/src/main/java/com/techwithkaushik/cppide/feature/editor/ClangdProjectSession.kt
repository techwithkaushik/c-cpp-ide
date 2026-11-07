package com.techwithkaushik.cppide.feature.editor

import android.net.Uri
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.toolchain.LinuxToolchainDiscovery
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.events.EventContext
import io.github.rosemoe.sora.lsp.events.EventListener
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorLspStatus(
    val label: String,
    val detail: String,
    val connected: Boolean = false,
    val error: Boolean = false
)

data class EditorProblem(
    val filePath: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val message: String
)

/**
 * Owns one clangd process per workspace and exposes Sora's native LSP-backed language.
 * The Linux workspace is bind-mounted at /workspace inside the selected Ubuntu rootfs.
 * No host shell, Termux service, TCP listener, or simulated completion provider is used.
 */
class ClangdProjectSession(
    workspaceDirectory: File,
    private val runtime: LinuxRuntimePaths,
    configuredClangdGuestPath: String?
) : AutoCloseable {

    private val workspace = workspaceDirectory.canonicalFile
    private val project = LspProject(GUEST_WORKSPACE)
    private var available = false
    private val problemsByFile = ConcurrentHashMap<String, List<EditorProblem>>()
    private val mutableStatus = MutableStateFlow(
        EditorLspStatus("clangd unavailable", "C/C++ language services have not been validated.", error = true)
    )
    private val mutableProblems = MutableStateFlow<List<EditorProblem>>(emptyList())

    val status: StateFlow<EditorLspStatus> = mutableStatus.asStateFlow()
    val problems: StateFlow<List<EditorProblem>> = mutableProblems.asStateFlow()

    private val diagnosticsListener = object : EventListener {
        override val eventName: String = "editor/publishDiagnostics"

        override fun handle(context: EventContext) {
            val editor = context.getOrNull<LspEditor>("lsp-editor") ?: return
            val relativePath = Uri.decode(editor.uri.path)
                .removePrefix("$GUEST_WORKSPACE/")
                .takeIf { it.isNotBlank() && it != editor.uri.path }
                ?: return
            val diagnostics = context.getOrNull<List<Diagnostic>>("data").orEmpty()
            val mapped = diagnostics.map { diagnostic ->
                EditorProblem(
                    filePath = relativePath,
                    line = diagnostic.range.start.line + 1,
                    column = diagnostic.range.start.character + 1,
                    severity = when (diagnostic.severity) {
                        DiagnosticSeverity.Error -> "Error"
                        DiagnosticSeverity.Warning -> "Warning"
                        DiagnosticSeverity.Information -> "Information"
                        DiagnosticSeverity.Hint -> "Hint"
                        else -> "Information"
                    },
                    message = diagnostic.message.orEmpty().ifBlank { "Language server reported a diagnostic." }
                )
            }
            if (mapped.isEmpty()) problemsByFile.remove(relativePath) else problemsByFile[relativePath] = mapped
            mutableProblems.value = problemsByFile.values.flatten()
                .sortedWith(compareBy<EditorProblem>({ it.filePath }, { it.line }, { it.column }))
        }
    }

    init {
        try {
            require(workspace.isDirectory) { "The selected project workspace does not exist." }
            runtime.ensureDirectories()
            require(runtime.ready()) { "Ubuntu rootfs or the bundled PRoot runtime is not ready." }
            val root = runtime.ubuntuRoot.canonicalFile
            val selectedClangdPath = configuredClangdGuestPath ?: LinuxToolchainDiscovery()
                .discover(root)
                .find("clangd")
                ?.executable
                ?.let { discovered -> "/" + File(discovered).canonicalFile.relativeTo(root).invariantSeparatorsPath }
                ?: throw IllegalStateException("clangd is not installed in the Ubuntu runtime. Install clangd from C/C++ Language Settings.")
            require(selectedClangdPath.startsWith("/") && '\u0000' !in selectedClangdPath) {
                "The configured clangd path must be an absolute Linux guest path."
            }
            val clangd = File(root, selectedClangdPath.removePrefix("/")).canonicalFile
            require(clangd.toPath().startsWith(root.toPath()) && clangd.isFile && clangd.canExecute()) {
                "clangd is missing or not executable at $selectedClangdPath. Install clangd in Ubuntu or correct its Language Settings path."
            }

            project.addServerDefinition(
                CustomLanguageServerDefinition(
                    "cpp",
                    CustomLanguageServerDefinition.ServerConnectProvider { _ ->
                        ProotClangdConnectionProvider(
                            runtime = runtime,
                            workspace = workspace,
                            clangdGuestPath = selectedClangdPath,
                            onUnexpectedExit = { exitCode, stderr ->
                                mutableStatus.value = EditorLspStatus(
                                    "clangd stopped",
                                    buildString {
                                        append("clangd exited with code ")
                                        append(exitCode)
                                        if (stderr.isNotBlank()) append(": ").append(stderr.takeLast(1200))
                                    },
                                    error = true
                                )
                            }
                        )
                    },
                    "clangd",
                    null,
                    listOf("c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")
                )
            )
            project.init()
            project.eventEmitter.addListener(diagnosticsListener)
            available = true
            mutableStatus.value = EditorLspStatus(
                "clangd installed",
                "Ubuntu runtime and clangd executable validated. The server starts when a C/C++ file opens."
            )
        } catch (error: Exception) {
            mutableStatus.value = EditorLspStatus(
                "clangd unavailable",
                error.message ?: error.javaClass.simpleName,
                error = true
            )
        }
    }

    /** Returns null for unsupported files or when the real runtime/tool is unavailable. */
    fun createEditor(relativeFilePath: String): LspEditor? {
        if (!available) return null
        val source = File(workspace, relativeFilePath).canonicalFile
        if (!source.toPath().startsWith(workspace.toPath()) || source == workspace || source.isDirectory) return null
        val extension = source.extension.lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) return null
        val relative = source.relativeTo(workspace).invariantSeparatorsPath
        val encodedGuestPath = Uri.encode("$GUEST_WORKSPACE/$relative", "/")
        return project.getOrCreateEditor(encodedGuestPath).apply {
            completionTriggers.addAll(listOf(".", ":", ">", "#"))
            signatureHelpTriggers.addAll(listOf("(", ","))
            signatureHelpReTriggers.add(",")
            isEnableHover = true
            isEnableSignatureHelp = true
        }
    }

    suspend fun connect(editor: LspEditor) {
        if (!available || editor.isConnected) return
        mutableStatus.value = EditorLspStatus("Starting clangd…", "Starting the Ubuntu clangd process and initializing LSP.")
        try {
            editor.connectWithTimeout()
            editor.isEnableInlayHint = true
            mutableStatus.value = EditorLspStatus(
                "clangd connected",
                "Completion, diagnostics, hover, signature help and code actions are available.",
                connected = true
            )
        } catch (error: Exception) {
            mutableStatus.value = EditorLspStatus(
                "clangd connection failed",
                error.message ?: error.javaClass.simpleName,
                error = true
            )
        }
    }

    override fun close() {
        project.eventEmitter.removeListener(diagnosticsListener)
        project.dispose()
        problemsByFile.clear()
        mutableProblems.value = emptyList()
        available = false
    }

    private companion object {
        const val GUEST_WORKSPACE = "/workspace"
        val SUPPORTED_EXTENSIONS = setOf("c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")
    }
}

private class ProotClangdConnectionProvider(
    private val runtime: LinuxRuntimePaths,
    private val workspace: File,
    private val clangdGuestPath: String,
    private val onUnexpectedExit: (Int, String) -> Unit
) : StreamConnectionProvider {

    @Volatile
    private var process: Process? = null
    private val closed = AtomicBoolean(false)
    private val stderr = StringBuilder()

    override fun start() {
        if (process != null) return
        val root = runtime.ubuntuRoot.canonicalFile
        val workspaceMount = File(root, "workspace").canonicalFile
        require(workspaceMount.toPath().startsWith(root.toPath())) {
            "The clangd workspace mount escapes the Ubuntu rootfs."
        }
        if (!workspaceMount.exists() && !workspaceMount.mkdirs()) {
            throw IOException("Cannot create the /workspace mount point inside Ubuntu.")
        }
        if (!workspaceMount.isDirectory) {
            throw IOException("The /workspace mount point inside Ubuntu is not a directory.")
        }
        val command = listOf(
            runtime.proot.absolutePath,
            "-0", "-v", "0",
            "-r", runtime.ubuntuRoot.absolutePath,
            "-w", "/workspace",
            "-b", "${workspace.absolutePath}:/workspace",
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            clangdGuestPath,
            "--background-index",
            "--clang-tidy",
            "--completion-style=detailed"
        )
        val child = ProcessBuilder(command).directory(workspace).apply {
            environment().clear()
            environment()["HOME"] = "/root"
            environment()["USER"] = "root"
            environment()["LOGNAME"] = "root"
            environment()["SHELL"] = "/bin/bash"
            environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            environment()["TMPDIR"] = "/tmp"
            environment()["PROOT_TMP_DIR"] = runtime.usrTmp.absolutePath
            environment()["PROOT_NO_SECCOMP"] = "1"
            environment()["PROOT_LOADER"] = runtime.prootLoader.absolutePath
            environment()["TERM"] = "xterm-256color"
            environment()["LANG"] = "C.UTF-8"
        }.start()
        process = child

        Thread({
            runCatching {
                child.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.length > MAX_STDERR_CHARS) stderr.delete(0, stderr.length - MAX_STDERR_CHARS)
                            stderr.append(line).append('\n')
                        }
                    }
                }
            }
        }, "clangd-stderr").apply { isDaemon = true; start() }

        Thread({
            val exitCode = runCatching { child.waitFor() }.getOrDefault(-1)
            if (!closed.get()) {
                val errorText = synchronized(stderr) { stderr.toString().trim() }
                onUnexpectedExit(exitCode, errorText)
            }
        }, "clangd-exit").apply { isDaemon = true; start() }
    }

    override val inputStream
        get() = process?.inputStream ?: throw IOException("clangd process has not been started.")

    override val outputStream
        get() = process?.outputStream ?: throw IOException("clangd process has not been started.")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val child = process ?: return
        runCatching { child.outputStream.close() }
        try {
            if (!child.waitFor(750, TimeUnit.MILLISECONDS)) {
                child.destroy()
                if (!child.waitFor(500, TimeUnit.MILLISECONDS)) child.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            child.destroyForcibly()
            Thread.currentThread().interrupt()
        }
        runCatching { child.inputStream.close() }
        runCatching { child.errorStream.close() }
        process = null
    }

    private companion object {
        const val MAX_STDERR_CHARS = 8 * 1024
    }
}
