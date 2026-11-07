package com.techwithkaushik.cppide.feature.editor

import com.techwithkaushik.cppide.core.contracts.editor.EditorDiagnostics
import com.techwithkaushik.cppide.core.contracts.runtime.LinuxRuntimeEnvironment
import com.techwithkaushik.cppide.core.contracts.runtime.LinuxToolchainAccess
import com.techwithkaushik.cppide.core.contracts.workspace.WorkspaceIdentity
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.events.EventContext
import io.github.rosemoe.sora.lsp.events.EventListener
import io.github.rosemoe.sora.lsp.events.AsyncEventListener
import io.github.rosemoe.sora.lsp.events.document.DocumentOpenEvent
import io.github.rosemoe.sora.lsp.utils.toFileUri
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.event.ContentChangeEvent
import org.eclipse.lsp4j.CompletionItem
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.future.await
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

/** Backward-compatible package alias; the model is owned by :core:contracts. */
typealias EditorProblem = com.techwithkaushik.cppide.core.contracts.editor.EditorProblem

/**
 * Owns one clangd process per workspace and exposes Sora's native LSP-backed language.
 * The Linux workspace is bind-mounted at /workspace inside the selected Ubuntu rootfs.
 * No host shell, Termux service, TCP listener, or simulated completion provider is used.
 */
class ClangdProjectSession(
    workspaceIdentity: WorkspaceIdentity,
    private val runtime: LinuxRuntimeEnvironment,
    private val toolchains: LinuxToolchainAccess,
    configuredClangdGuestPath: String?
) : EditorDiagnostics, AutoCloseable {

    private val workspace = workspaceIdentity.root.canonicalFile
    private val project = LspProject(GUEST_WORKSPACE)
    private var available = false
    private val disposed = AtomicBoolean(false)
    private val problemsByFile = ConcurrentHashMap<String, List<EditorProblem>>()
    private val connectionAttempts = ConcurrentHashMap.newKeySet<LspEditor>()
    private val eventLoggedEditors = ConcurrentHashMap.newKeySet<LspEditor>()
    private var previousProjectThrowableListener: ((Throwable) -> Unit)? = null
    private val mutableStatus = MutableStateFlow(
        EditorLspStatus("clangd unavailable", "C/C++ language services have not been validated.", error = true)
    )
    private val mutableProblems = MutableStateFlow<List<EditorProblem>>(emptyList())

    val status: StateFlow<EditorLspStatus> = mutableStatus.asStateFlow()
    override val problems: StateFlow<List<EditorProblem>> = mutableProblems.asStateFlow()

    private val diagnosticsListener = object : EventListener {
        override val eventName: String = "editor/publishDiagnostics"

        override fun handle(context: EventContext) {
            val editor = context.getOrNull<LspEditor>("lsp-editor") ?: return
            // FileUri stores the raw filesystem path; its toUri() method performs URI escaping.
            // Decoding here would corrupt literal '%' sequences and encoded paths with spaces.
            val relativePath = editor.uri.path
                .removePrefix("$GUEST_WORKSPACE/")
                .takeIf { it.isNotBlank() && it != editor.uri.path }
                ?.let { candidate ->
                    runCatching { File(workspace, candidate).canonicalFile }
                        .getOrNull()
                        ?.takeIf { it.toPath().startsWith(workspace.toPath()) }
                        ?.relativeTo(workspace)
                        ?.invariantSeparatorsPath
                }
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
            DeveloperLog.event("ClangdLsp", "diagnostics_received", mapOf("file" to relativePath, "count" to mapped.size, "errors" to mapped.count { it.severity == "Error" }, "warnings" to mapped.count { it.severity == "Warning" }, "workspaceProblemCount" to mutableProblems.value.size))
        }
    }

    init {
        val initializationStartedAt = System.nanoTime()
        DeveloperLog.event("ClangdLsp", "session_initialization_started", mapOf("workspace" to workspace.absolutePath, "workspaceExists" to workspace.isDirectory, "configuredClangd" to configuredClangdGuestPath))
        try {
            require(workspace.isDirectory) { "The selected project workspace does not exist." }
            DeveloperLog.event("ClangdLsp", "workspace_validated", mapOf("workspace" to workspace.absolutePath))
            runtime.ensureDirectories()
            DeveloperLog.event("ClangdLsp", "runtime_directories_ready", mapOf("rootfs" to runtime.ubuntuRoot.absolutePath, "rootfsExists" to runtime.ubuntuRoot.isDirectory, "proot" to runtime.proot.absolutePath, "prootExists" to runtime.proot.isFile, "loaderExists" to runtime.prootLoader.isFile))
            require(runtime.ready()) { "Ubuntu rootfs or the bundled PRoot runtime is not ready." }
            val root = runtime.ubuntuRoot.canonicalFile
            ensureFallbackCompilationDatabase(root)
            
            val selectedClangdPath = configuredClangdGuestPath?.also { configured ->
                require(toolchains.resolveExecutable(root, configured) != null) {
                    "Configured clangd path '$configured' does not resolve to an executable inside Ubuntu. Clear the override or select a valid guest path in C/C++ Language Settings."
                }
            } ?: toolchains.findExecutable(root, "clangd")
                ?.let { discovered -> toolchains.toGuestPath(root, discovered) }
                ?: throw IllegalStateException("clangd is not installed in the Ubuntu runtime. Install clangd from C/C++ Language Settings.")
            val clangd = requireNotNull(toolchains.resolveExecutable(root, selectedClangdPath)) {
                "clangd is missing or not executable at $selectedClangdPath. Install clangd in Ubuntu or correct its Language Settings path."
            }
            DeveloperLog.event("ClangdLsp", "clangd_executable_resolved", mapOf("configuredPath" to configuredClangdGuestPath, "selectedGuestPath" to selectedClangdPath, "resolvedHostPath" to clangd.absolutePath, "executable" to clangd.canExecute(), "sizeBytes" to clangd.length()))

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
            // clangd handles both C and C++. Register one definition for every
            // supported extension so Sora reuses one workspace-scoped process
            // instead of launching separate daemons for .c and .cpp documents.
            project.addServerDefinition(
                CustomLanguageServerDefinition(
                    ext = "cpp",
                    serverConnectProvider = clangdConnectionProvider,
                    name = "clangd",
                    extensionsOverride = (listOf("c") + CPP_EXTENSIONS).distinct()
                )
            )
            project.init()
            // Sora 0.24.4 sends the file extension as textDocument.languageId.
            // clangd expects protocol language IDs ("c" or "cpp"), not "hpp"/"cc".
            project.eventEmitter.removeListener(DocumentOpenEvent::class.java)
            project.eventEmitter.addListener(ClangdDocumentOpenEvent())
            project.eventEmitter.addListener(diagnosticsListener)
            previousProjectThrowableListener = project.eventEmitter.throwableListener
            project.eventEmitter.throwableListener = { error ->
                DeveloperLog.failure("ClangdLsp", "project_event_dispatch_failed", error, mapOf("workspace" to workspace.absolutePath))
                requireNotNull(previousProjectThrowableListener).invoke(error)
            }
            available = true
            DeveloperLog.event("ClangdLsp", "session_ready", mapOf(
                "durationMs" to (System.nanoTime() - initializationStartedAt) / 1_000_000,
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
            DeveloperLog.failure("ClangdLsp", "session_initialization_failed", error, mapOf("workspace" to workspace.absolutePath, "workspaceExists" to workspace.isDirectory, "rootfsExists" to runtime.ubuntuRoot.isDirectory, "prootExists" to runtime.proot.isFile, "loaderExists" to runtime.prootLoader.isFile, "durationMs" to (System.nanoTime() - initializationStartedAt) / 1_000_000))
            mutableStatus.value = EditorLspStatus(
                "clangd unavailable",
                error.message ?: error.javaClass.simpleName,
                error = true
            )
        }
    }


    @Synchronized
    private fun ensureFallbackCompilationDatabase(root: File) {
        val database = File(workspace, "compile_commands.json")
        val marker = File(workspace, ".cppide-compile-commands.generated")
        if (database.exists() && !marker.exists()) return
        fun guestCompiler(name: String): String? {
            val executable = toolchains.findExecutable(root, name)?.canonicalFile ?: return null
            if (!executable.toPath().startsWith(root.toPath()) || !executable.isFile || !executable.canExecute()) return null
            return "/" + executable.relativeTo(root).invariantSeparatorsPath
        }
        val cc = guestCompiler("clang") ?: guestCompiler("gcc")
        // clangd has a built-in fallback command. A missing compiler must not
        // prevent initialization, completion, hover and basic diagnostics.
        if (cc == null) {
            DeveloperLog.event("ClangdLsp", "fallback_compile_commands_skipped", mapOf("reason" to "compiler_missing"))
            if (marker.exists()) { database.delete(); marker.delete() }
            return
        }
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
        if (!available || disposed.get()) return null
        val source = File(workspace, relativeFilePath).canonicalFile
        if (!source.toPath().startsWith(workspace.toPath()) || source == workspace || source.isDirectory) return null
        val extension = source.extension.lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) return null
        // New source files may be created after session startup. Refresh only
        // IDE-generated compile commands; never overwrite user-managed databases.
        runCatching { ensureFallbackCompilationDatabase(runtime.ubuntuRoot.canonicalFile) }
            .onFailure { DeveloperLog.failure("ClangdLsp", "compile_commands_refresh_failed", it) }
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
        if (!available || disposed.get()) { DeveloperLog.warn("ClangdLsp", "connect_skipped_session_unavailable; file=${editor.uri.path}"); return }
        if (editor.isConnected) { DeveloperLog.debug("ClangdLsp", "connect_skipped_already_connected; file=${editor.uri.path}"); return }
        if (!connectionAttempts.add(editor)) { DeveloperLog.debug("ClangdLsp", "connect_skipped_attempt_in_progress; file=${editor.uri.path}"); return }
        if (editor.editor == null) {
            DeveloperLog.warn("ClangdLsp", "connect_skipped_editor_not_attached; file=${editor.uri.path}")
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
        val connectStartedAt = System.nanoTime()
        try {
            DeveloperLog.event("ClangdLsp", "initialize_handshake_started", mapOf("file" to editor.uri.path, "timeoutMs" to LSP_CONNECT_TIMEOUT_MS))
            val connected = withTimeoutOrNull(LSP_CONNECT_TIMEOUT_MS) { editor.connect() }
                ?: throw IllegalStateException("clangd did not respond to the LSP initialize request within 45 seconds.")
            DeveloperLog.event("ClangdLsp", "initialize_handshake_completed", mapOf("file" to editor.uri.path, "connectedResult" to connected, "editorConnected" to editor.isConnected, "durationMs" to (System.nanoTime() - connectStartedAt) / 1_000_000))
            if (!connected || !editor.isConnected) {
                throw IllegalStateException("clangd did not complete the LSP initialize handshake. Check the clangd executable, PRoot loader and runtime logs.")
            }
            installEditorEventLogging(editor)
            editor.isEnableInlayHint = true
            // Sora Editor LSP 0.24.4 exposes getServerCapabilities(); there is no
            // getCachedServerCapabilities() API. connect() has completed the initialize
            // handshake above, so this returns the negotiated/effective capabilities.
            val capabilities = editor.languageServerWrapper.getServerCapabilities()
            val advertisedFeatures = buildList {
                if (capabilities?.completionProvider != null) add("completion")
                if (capabilities?.hoverProvider != null) add("hover")
                if (capabilities?.definitionProvider != null) add("definition")
                if (capabilities?.referencesProvider != null) add("references")
                if (capabilities?.signatureHelpProvider != null) add("signature help")
                if (capabilities?.documentFormattingProvider != null) add("formatting")
                if (capabilities?.renameProvider != null) add("rename")
                if (capabilities?.codeActionProvider != null) add("code actions")
                if (capabilities?.documentSymbolProvider != null) add("document symbols")
                if (capabilities?.semanticTokensProvider != null) add("semantic tokens")
                if (capabilities?.inlayHintProvider != null) add("inlay hints")
            }
            DeveloperLog.event("ClangdLsp", "server_capabilities_received", mapOf("file" to editor.uri.path, "requestManagers" to editor.requestManagers.size, "advertisedFeatures" to advertisedFeatures, "completionProviderPresent" to (capabilities?.completionProvider != null)))
            val completionStartedAt = System.nanoTime()
            val completionResponse = if (capabilities?.completionProvider != null) {
                DeveloperLog.event("ClangdLsp", "completion_probe_started", mapOf("file" to editor.uri.path, "timeoutMs" to FEATURE_PROBE_TIMEOUT_MS))
                withTimeoutOrNull(FEATURE_PROBE_TIMEOUT_MS) {
                    editor.eventManager.emitAsync("textDocument/completion", CharPosition(0, 0))
                }
            } else {
                null
            }
            val completionItems = completionResponse
                ?.getOrNull<List<org.eclipse.lsp4j.CompletionItem>>("completion-items")
            val completionProbe = when {
                capabilities?.completionProvider == null -> "not advertised"
                completionResponse == null -> "request timed out"
                completionItems == null -> "no response payload"
                else -> "responded (${completionItems.size} items)"
            }
            DeveloperLog.event("ClangdLsp", "completion_probe_finished", mapOf("file" to editor.uri.path, "result" to completionProbe, "itemCount" to completionItems?.size, "durationMs" to (System.nanoTime() - completionStartedAt) / 1_000_000))
            val featureDetail = buildString {
                append("LSP initialize and didOpen succeeded. ")
                append("Server features: ")
                append(advertisedFeatures.takeIf { it.isNotEmpty() }?.joinToString() ?: "none advertised")
                append(". Completion request: ")
                append(completionProbe)
                append(".")
            }
            mutableStatus.value = EditorLspStatus(
                "clangd connected",
                featureDetail,
                connected = true,
                error = completionResponse == null && capabilities?.completionProvider != null
            )
            DeveloperLog.event("ClangdLsp", "connect_succeeded", mapOf(
                "file" to editor.uri.path,
                "connected" to editor.isConnected,
                "requestManagers" to editor.requestManagers.size,
                "advertisedFeatures" to advertisedFeatures,
                "completionProbe" to completionProbe,
                "completionItemCount" to completionItems?.size
            ))
        } catch (error: CancellationException) {
            connectionAttempts.remove(editor)
            throw error
        } catch (error: Exception) {
            DeveloperLog.failure("ClangdLsp", "connect_failed", error, mapOf("file" to editor.uri.path, "attached" to (editor.editor != null), "editorConnected" to editor.isConnected, "durationMs" to (System.nanoTime() - connectStartedAt) / 1_000_000))
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
            // A failed handshake must not permanently suppress a later retry
            // after the user fixes the runtime or clangd installation.
            connectionAttempts.remove(editor)
        }
    }

    /**
     * Observe Sora's real event dispatch after its built-in handler has completed.
     * This records protocol operations without logging source text or request payloads.
     */
    private fun installEditorEventLogging(editor: LspEditor) {
        if (!eventLoggedEditors.add(editor)) return
        val methods = listOf(
            "textDocument/completion",
            "textDocument/hover",
            "textDocument/signatureHelp",
            "textDocument/formatting",
            "textDocument/rangeFormatting",
            "textDocument/codeAction",
            "textDocument/documentHighlight",
            "textDocument/inlayHint",
            "textDocument/documentColor",
            "textDocument/didSave",
            "textDocument/didClose"
        )
        methods.forEach { method ->
            project.eventEmitter.addListener(ClangdProtocolEventListener(editor, method))
        }
        DeveloperLog.event("ClangdLsp", "editor_event_logging_installed", mapOf(
            "file" to editor.uri.path,
            "observedMethods" to methods
        ))
    }

    /** Notify clangd after the corresponding workspace file has been written successfully. */
    fun saveDocument(relativeFilePath: String) {
        val startedAt = System.nanoTime()
        DeveloperLog.event("ClangdLsp", "document_save_requested", mapOf("file" to relativeFilePath, "sessionAvailable" to available))
        val safeFile = runCatching { File(workspace, relativeFilePath).canonicalFile }
            .onFailure { DeveloperLog.failure("ClangdLsp", "document_save_path_resolution_failed", it, mapOf("file" to relativeFilePath)) }
            .getOrNull() ?: return
        if (!safeFile.toPath().startsWith(workspace.toPath())) {
            DeveloperLog.warn("ClangdLsp", "document_save_rejected_outside_workspace; file=$relativeFilePath")
            return
        }
        val guestPath = "$GUEST_WORKSPACE/${safeFile.relativeTo(workspace).invariantSeparatorsPath}"
        // New files and renamed source files need fresh compilation commands.
        // Refresh before didSave so clangd can pick up the updated database.
        runCatching { ensureFallbackCompilationDatabase(runtime.ubuntuRoot.canonicalFile) }
            .onFailure { DeveloperLog.failure("ClangdLsp", "compile_commands_refresh_failed", it, mapOf("file" to relativeFilePath)) }
        val editor = project.getEditor(guestPath) ?: run {
            DeveloperLog.debug("ClangdLsp", "document_save_skipped_editor_not_registered; file=$relativeFilePath")
            return
        }
        if (!editor.isConnected) {
            DeveloperLog.warn("ClangdLsp", "document_save_skipped_server_disconnected; file=$relativeFilePath")
            return
        }
        editor.coroutineScope.launch {
            DeveloperLog.event("ClangdLsp", "did_save_sending", mapOf("file" to relativeFilePath, "uri" to editor.uri.path))
            runCatching { editor.saveDocument() }
                .onSuccess {
                    DeveloperLog.event("ClangdLsp", "did_save_sent", mapOf("file" to relativeFilePath, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                }
                .onFailure { error ->
                    DeveloperLog.failure("ClangdLsp", "document_save_notification_failed", error, mapOf("file" to relativeFilePath))
                }
        }
    }

    /**
     * Detach the workspace immediately and perform Sora/LSP/process teardown away from
     * the Compose thread. project.dispose() closes language-server transports and can
     * wait for child processes; doing that from DisposableEffect freezes project navigation.
     */
    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        available = false
        val attempts = connectionAttempts.size
        val problemCount = mutableProblems.value.size
        problemsByFile.clear()
        connectionAttempts.clear()
        mutableProblems.value = emptyList()
        DeveloperLog.event("ClangdLsp", "session_dispose_requested", mapOf(
            "workspace" to workspace.absolutePath,
            "connectionAttempts" to attempts,
            "problemCount" to problemCount,
            "thread" to Thread.currentThread().name
        ))
        SESSION_DISPOSER.execute {
            val startedAt = System.nanoTime()
            runCatching {
                project.eventEmitter.removeListener(diagnosticsListener)
                project.eventEmitter.removeListener(ClangdProtocolEventListener::class.java)
                previousProjectThrowableListener?.let { project.eventEmitter.throwableListener = it }
                previousProjectThrowableListener = null
                project.dispose()
                eventLoggedEditors.clear()
                DeveloperLog.event("ClangdLsp", "session_disposed", mapOf(
                    "workspace" to workspace.absolutePath,
                    "durationMs" to (System.nanoTime() - startedAt) / 1_000_000
                ))
            }.onFailure { error ->
                DeveloperLog.failure("ClangdLsp", "session_dispose_failed", error, mapOf("workspace" to workspace.absolutePath))
            }
        }
    }

    companion object {
        /** Sora editor disposal may close an LSP transport; never run it on Compose's UI thread. */
        fun disposeEditorAsync(editor: LspEditor?) {
            if (editor == null) return
            SESSION_DISPOSER.execute {
                runCatching { editor.dispose() }
                    .onFailure { DeveloperLog.failure("ClangdLsp", "editor_dispose_failed", it, mapOf("file" to editor.uri.path)) }
            }
        }

        const val LSP_CONNECT_TIMEOUT_MS = 45_000L
        const val FEATURE_PROBE_TIMEOUT_MS = 12_000L
        const val GUEST_WORKSPACE = "/workspace"
        val SESSION_DISPOSER = Executors.newCachedThreadPool(ThreadFactory { task ->
            Thread(task, "cppide-lsp-dispose").apply { isDaemon = true }
        })
        val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")
        val EXCLUDED_SOURCE_DIRECTORIES = setOf(".git", ".cppide-build", "build", "out", ".gradle")
        val CPP_EXTENSIONS = CPP_DOCUMENT_EXTENSIONS
        val SUPPORTED_EXTENSIONS = CPP_EXTENSIONS + "c"
    }
}


private class ClangdDocumentOpenEvent : AsyncEventListener() {
    override val eventName: String = "textDocument/didOpen"

    override suspend fun handleAsync(context: EventContext) {
        val editor = context.get<LspEditor>("lsp-editor")
        val languageId = clangdLanguageIdForExtension(editor.fileExt)
            ?: throw IllegalArgumentException("Unsupported clangd document extension: ${editor.fileExt}")
        val startedAt = System.nanoTime()
        DeveloperLog.event("ClangdLsp", "did_open_sending", mapOf("uri" to editor.uri.path, "extension" to editor.fileExt, "languageId" to languageId, "documentLength" to editor.editorContent.length, "version" to 0))
        val params = DidOpenTextDocumentParams().apply {
            // Start at version zero; Sora's incremental-change version counter
            // starts at one for this URI on the first didChange notification.
            textDocument = TextDocumentItem(
                editor.uri.toFileUri(),
                languageId,
                0,
                editor.editorContent
            )
        }
        try {
            CompletableFuture.runAsync { editor.requestManager.didOpen(params) }.await()
            DeveloperLog.event("ClangdLsp", "did_open_sent", mapOf("uri" to editor.uri.path, "languageId" to languageId, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
        } catch (error: Exception) {
            DeveloperLog.failure("ClangdLsp", "did_open_failed", error, mapOf("uri" to editor.uri.path, "languageId" to languageId, "documentLength" to editor.editorContent.length))
            throw error
        }
    }
}


private class ClangdProtocolEventListener(
    private val targetEditor: LspEditor,
    override val eventName: String
) : EventListener {
    private val sequence = AtomicInteger()

    override fun handle(context: EventContext) {
        val sourceEditor = context.getOrNull<LspEditor>("lsp-editor") ?: return
        if (sourceEditor !== targetEditor) return

        val position = context.getOrNull<CharPosition>(CharPosition::class.java.name)
        val change = context.getOrNull<ContentChangeEvent>(ContentChangeEvent::class.java.name)
        val completionItems = context.getOrNull<List<CompletionItem>>("completion-items")
        DeveloperLog.debug("ClangdLsp", buildString {
            append("event=editor_lsp_event_completed")
            append(" method=").append(eventName)
            append(" file=").append(sourceEditor.uri.path)
            append(" sequence=").append(sequence.incrementAndGet())
            append(" connected=").append(sourceEditor.isConnected)
            if (position != null) {
                append(" line=").append(position.line + 1)
                append(" column=").append(position.column + 1)
            }
            if (change != null) {
                append(" changeAction=").append(change.action)
                append(" changedCharacters=").append(change.changedText.length)
            }
            if (completionItems != null) append(" completionItems=").append(completionItems.size)
        })
    }
}

internal fun clangdLanguageIdForExtension(extension: String): String? = when (extension.lowercase()) {
    "c" -> "c"
    in CPP_DOCUMENT_EXTENSIONS -> "cpp"
    else -> null
}

private val CPP_DOCUMENT_EXTENSIONS = setOf("cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")

private class ProotClangdConnectionProvider(
    private val runtime: LinuxRuntimeEnvironment,
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
            "-0", "-v", "0", "--link2symlink",
            "-r", runtime.ubuntuRoot.absolutePath,
            "-w", "/workspace",
            "-b", "${workspace.absolutePath}:/workspace",
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
            clangdGuestPath,
            "--background-index",
            "--completion-style=detailed",
            "--compile-commands-dir=/workspace"
        )
        DeveloperLog.event("ClangdLsp", "proot_process_start_requested", mapOf("command" to command.joinToString(" "), "rootfsExists" to root.isDirectory, "prootExists" to runtime.proot.isFile, "loaderExists" to runtime.prootLoader.isFile, "workspaceMount" to workspaceMount.absolutePath))
        val child = try {
            ProcessBuilder(command).directory(workspace).apply {
            // Keep Android host linker variables out of the Ubuntu guest. PRoot's
            // loader is selected explicitly; leaking the APK nativeLibraryDir into
            // guest LD_LIBRARY_PATH can make clangd load Android .so files instead
            // of Ubuntu's glibc libraries.
            environment().remove("LD_LIBRARY_PATH")
            environment().remove("LD_PRELOAD")
            environment().remove("PREFIX")
            environment().remove("BOOTSTRAP_ROOT")
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
        } catch (error: Exception) {
            DeveloperLog.failure("ClangdLsp", "proot_process_start_failed", error, mapOf("command" to command.joinToString(" "), "rootfsExists" to root.isDirectory, "workspaceMountExists" to workspaceMount.isDirectory))
            throw error
        }
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
                        DeveloperLog.warn("ClangdLsp", "clangd_stderr: ${line.take(1800)}")
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
