package com.techwithkaushik.cppide.feature.editor

import com.techwithkaushik.cppide.core.logging.DeveloperLog
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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
    private val connectionAttempts = ConcurrentHashMap.newKeySet<LspEditor>()
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
            // FileUri stores the raw filesystem path; its toUri() method performs URI escaping.
            // Decoding here would corrupt literal '%' sequences and encoded paths with spaces.
            val relativePath = editor.uri.path
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
            ensureFallbackCompilationDatabase(root)
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

            val clangdConnectionProvider = CustomLanguageServerDefinition.ServerConnectProvider { _ ->
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
            }

            // Sora 0.24.4 keys server definitions by every declared extension.
            // Keep one definition per language family and map all C++ extensions to
            // the same clangd wrapper so opening .cc/.hpp files does not spawn new daemons.
            project.addServerDefinition(
                CustomLanguageServerDefinition(
                    ext = "c",
                    serverConnectProvider = clangdConnectionProvider,
                    name = "clangd-c",
                    extensionsOverride = listOf("c")
                )
            )
            project.addServerDefinition(
                CustomLanguageServerDefinition(
                    ext = "cpp",
                    serverConnectProvider = clangdConnectionProvider,
                    name = "clangd-cpp",
                    extensionsOverride = CPP_EXTENSIONS.toList()
                )
            )
            project.init()
            project.eventEmitter.addListener(diagnosticsListener)
            available = true
            DeveloperLog.event("ClangdLsp", "session_ready", mapOf(
                "workspace" to workspace.absolutePath,
                "guestWorkspace" to GUEST_WORKSPACE,
                "clangdPath" to selectedClangdPath,
                "prootExists" to runtime.proot.isFile,
                "loaderExists" to runtime.prootLoader.isFile
            ))
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


    private fun ensureFallbackCompilationDatabase(root: File) {
        val database = File(workspace, "compile_commands.json")
        val marker = File(workspace, ".cppide-compile-commands.generated")
        if (database.exists() && !marker.exists()) return
        val discovery = LinuxToolchainDiscovery().discover(root)
        fun guestCompiler(name: String): String? {
            val raw = discovery.find(name)?.executable ?: return null
            val executable = File(raw).canonicalFile
            if (!executable.toPath().startsWith(root.toPath()) || !executable.isFile || !executable.canExecute()) return null
            return "/" + executable.relativeTo(root).invariantSeparatorsPath
        }
        val cc = guestCompiler("clang") ?: guestCompiler("gcc")
            ?: throw IllegalStateException("Install clang or gcc in Ubuntu to enable C/C++ language services.")
        val cxx = guestCompiler("clang++") ?: guestCompiler("g++") ?: cc
        val entries = JSONArray()
        workspace.walkTopDown()
            .onEnter { it == workspace || it.name !in EXCLUDED_SOURCE_DIRECTORIES }
            .filter { it.isFile && it.extension.lowercase() in SOURCE_EXTENSIONS }
            .forEach { file ->
                val guestFile = "$GUEST_WORKSPACE/" + file.relativeTo(workspace).invariantSeparatorsPath
                val isC = file.extension.equals("c", true)
                entries.put(JSONObject().apply {
                    put("directory", GUEST_WORKSPACE)
                    put("file", guestFile)
                    put("arguments", JSONArray().apply {
                        put(if (isC) cc else cxx)
                        put(if (isC) "-std=c17" else "-std=c++20")
                        put("-I$GUEST_WORKSPACE"); put("-I/usr/local/include"); put("-I/usr/include")
                        put("-c"); put(guestFile)
                    })
                })
            }
        val temp = File(workspace, "compile_commands.json.tmp")
        try {
            temp.writeText(entries.toString(2), Charsets.UTF_8)
            if (database.exists() && !marker.exists()) return
            if (!temp.renameTo(database)) {
                database.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8); temp.delete()
            }
            marker.writeText("cppide-fallback-v1\n", Charsets.UTF_8)
            DeveloperLog.event("ClangdLsp", "fallback_compile_commands_written", mapOf("entries" to entries.length()))
        } finally { if (temp.exists()) temp.delete() }
    }

    /** Returns null for unsupported files or when the real runtime/tool is unavailable. */
    fun createEditor(relativeFilePath: String): LspEditor? {
        if (!available) return null
        val source = File(workspace, relativeFilePath).canonicalFile
        if (!source.toPath().startsWith(workspace.toPath()) || source == workspace || source.isDirectory) return null
        val extension = source.extension.lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) return null
        val relative = source.relativeTo(workspace).invariantSeparatorsPath
        // Pass a filesystem path, not an already-encoded URI: FileUri.toUri() encodes it once.
        val guestPath = "$GUEST_WORKSPACE/$relative"
        val editor = (project.getEditor(guestPath) ?: project.createEditor(guestPath)).apply {
            completionTriggers.addAll(listOf(".", ":", ">", "#"))
            signatureHelpTriggers.addAll(listOf("(", ","))
            signatureHelpReTriggers.add(",")
            isEnableHover = true
            isEnableSignatureHelp = true
        }
        DeveloperLog.event("ClangdLsp", "document_editor_created", mapOf(
            "file" to relative,
            "extension" to extension,
            "uri" to editor.uri.toUri().toASCIIString(),
            "attached" to (editor.editor != null)
        ))
        return editor
    }

    suspend fun connect(editor: LspEditor) {
        if (!available || editor.isConnected || !connectionAttempts.add(editor)) return
        if (editor.editor == null) {
            connectionAttempts.remove(editor)
            mutableStatus.value = EditorLspStatus(
                "clangd not started",
                "The C/C++ document is not attached to Sora yet; reopen the file to retry.",
                error = true
            )
            return
        }
        DeveloperLog.event("ClangdLsp", "connect_requested", mapOf(
            "file" to editor.uri.path,
            "attached" to (editor.editor != null),
            "alreadyConnected" to editor.isConnected
        ))
        mutableStatus.value = EditorLspStatus(
            "Starting clangd…",
            "Launching clangd through Ubuntu/PRoot and waiting for the LSP initialize handshake."
        )
        try {
            val connected = withTimeoutOrNull(LSP_CONNECT_TIMEOUT_MS) { editor.connect() }
                ?: throw IllegalStateException("clangd did not respond to the LSP initialize request within 45 seconds.")
            if (!connected || !editor.isConnected) {
                throw IllegalStateException("clangd did not complete the LSP initialize handshake. Check the clangd executable, PRoot loader and runtime logs.")
            }
            editor.isEnableInlayHint = true
            mutableStatus.value = EditorLspStatus(
                "clangd connected",
                "LSP initialized and the document was opened. Completion, diagnostics, hover and signature help are active.",
                connected = true
            )
            DeveloperLog.event("ClangdLsp", "connect_succeeded", mapOf(
                "file" to editor.uri.path,
                "connected" to editor.isConnected,
                "requestManagers" to editor.requestManagers.size
            ))
        } catch (error: CancellationException) {
            connectionAttempts.remove(editor)
            throw error
        } catch (error: Exception) {
            val reason = generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                .distinct()
                .joinToString(" → ")
                .ifBlank { error.javaClass.simpleName }
            mutableStatus.value = EditorLspStatus(
                "clangd connection failed",
                reason,
                error = true
            )
            DeveloperLog.failure("ClangdLsp", "connect_failed", error, mapOf(
                "file" to editor.uri.path,
                "attached" to (editor.editor != null)
            ))
            // A failed handshake must not permanently suppress a later retry
            // after the user fixes the runtime or clangd installation.
            connectionAttempts.remove(editor)
        }
    }

    /** Notify clangd after the corresponding workspace file has been written successfully. */
    fun saveDocument(relativeFilePath: String) {
        val safeFile = File(workspace, relativeFilePath).canonicalFile
        if (!safeFile.toPath().startsWith(workspace.toPath())) return
        val guestPath = "$GUEST_WORKSPACE/${safeFile.relativeTo(workspace).invariantSeparatorsPath}"
        val editor = project.getEditor(guestPath) ?: return
        if (!editor.isConnected) return
        editor.coroutineScope.launch {
            runCatching { editor.saveDocument() }
                .onSuccess {
                    DeveloperLog.event("ClangdLsp", "document_saved", mapOf("file" to relativeFilePath))
                }
                .onFailure { error ->
                    DeveloperLog.failure("ClangdLsp", "document_save_notification_failed", error, mapOf("file" to relativeFilePath))
                }
        }
    }

    override fun close() {
        project.eventEmitter.removeListener(diagnosticsListener)
        project.dispose()
        problemsByFile.clear()
        connectionAttempts.clear()
        mutableProblems.value = emptyList()
        available = false
    }

    private companion object {
        const val LSP_CONNECT_TIMEOUT_MS = 45_000L
        const val GUEST_WORKSPACE = "/workspace"
        val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")
        val EXCLUDED_SOURCE_DIRECTORIES = setOf(".git", ".cppide-build", "build", "out", ".gradle")
        val CPP_EXTENSIONS = setOf("cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")
        val SUPPORTED_EXTENSIONS = CPP_EXTENSIONS + "c"
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
            "--completion-style=detailed",
            "--compile-commands-dir=/workspace"
        )
        val child = ProcessBuilder(command).directory(workspace).apply {
            // Preserve Android linker/runtime variables required by the APK-bundled PRoot ELF.
            val inheritedLibraryPath = environment()["LD_LIBRARY_PATH"].orEmpty()
            runtime.nativeLibraryDir?.absolutePath?.let { nativeDir ->
                environment()["LD_LIBRARY_PATH"] = listOf(nativeDir, inheritedLibraryPath)
                    .filter(String::isNotBlank)
                    .joinToString(File.pathSeparator)
            }
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
        DeveloperLog.event("ClangdLsp", "proot_process_started", mapOf(
            "proot" to runtime.proot.absolutePath,
            "nativeLibraryDir" to runtime.nativeLibraryDir?.absolutePath,
            "clangd" to clangdGuestPath,
            "workspace" to workspace.absolutePath
        ))

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
                DeveloperLog.event("ClangdLsp", "proot_process_exited", mapOf(
                    "exitCode" to exitCode,
                    "stderr" to errorText.takeLast(1200)
                ))
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
