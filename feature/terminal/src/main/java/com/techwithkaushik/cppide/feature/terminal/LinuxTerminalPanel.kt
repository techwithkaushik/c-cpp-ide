package com.techwithkaushik.cppide.feature.terminal

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.integration.linuxruntime.HostKeyConfirmationRequired
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimeSmokeTest
import com.techwithkaushik.cppide.integration.linuxruntime.ProotDownloader
import com.techwithkaushik.cppide.integration.linuxruntime.LocalLinuxSession
import com.techwithkaushik.cppide.integration.linuxruntime.SshHost
import com.techwithkaushik.cppide.integration.linuxruntime.SshShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.icu.text.BreakIterator
import java.util.Locale
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private val DEFAULT_TERMINAL_EXTRA_KEYS = setOf("ESC", "TAB", "CTRL", "ALT", "HOME", "UP", "END", "PGUP", "LEFT", "DOWN", "RIGHT", "PGDN")
private val TERMINAL_EXTRA_KEY_ORDER = listOf("ESC", "HOME", "UP", "END", "PGUP", "TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")

private data class LinuxTerminalTab(
    val id: Int,
    val title: MutableState<String>,
    val output: MutableState<AnnotatedString>,
    val outputRevision: MutableState<Int>,
    val screen: TerminalScreen,
    val send: (String) -> Unit,
    val interrupt: () -> Unit,
    val session: AutoCloseable,
    val resize: (Int, Int) -> Unit,
    val exited: MutableState<Int?>,
    val ended: AtomicBoolean = AtomicBoolean(false),
    var lastPtyColumns: Int = 0,
    var lastPtyRows: Int = 0,
)

private object LinuxTerminalSessions {
    // PTY readers can produce hundreds of small chunks per second (apt/dpkg is
    // particularly chatty). Coalesce them before rebuilding the annotated
    // scrollback. TerminalScreen mutations run on renderExecutor; Compose state
    // mutations are posted back to the main looper.
    private val outputHandler = Handler(Looper.getMainLooper())
    // Keep PTY coalescing timers off the main looper. If Compose is busy laying
    // out a large terminal snapshot, incoming output must still be drained.
    private val flushThread = HandlerThread("terminal-output-flush").apply { start() }
    private val flushHandler = Handler(flushThread.looper)
    private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terminal-render").apply { isDaemon = true }
    }
    private val outputLock = Any()
    private val pendingOutput = HashMap<Int, StringBuilder>()
    private val readyOutput = HashMap<Int, StringBuilder>()
    private val scheduledFlushes = HashSet<Int>()
    private val scheduledRenders = HashSet<Int>()
    private val activeTabIds = ConcurrentHashMap.newKeySet<Int>()
    private const val OUTPUT_FRAME_INTERVAL_MS = 32L

    val tabs = mutableStateListOf<LinuxTerminalTab>()
    var selectedId by mutableIntStateOf(0)
        private set
    // Plain volatile mirror: render workers must not read Compose snapshot state.
    @Volatile private var selectedTabIdForWorker = 0
    var onLastSessionExited: (() -> Unit)? = null
    private var nextId = 1

    fun select(id: Int) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        selectedId = id
        selectedTabIdForWorker = id
        // Snapshot construction is intentionally off the UI thread. The single
        // worker preserves byte order with pending PTY output and resize events.
        renderExecutor.execute {
            val rendered = tab.screen.renderAnnotated()
            outputHandler.post {
                if (selectedId == tab.id && tabs.any { it.id == tab.id }) {
                    tab.output.value = rendered
                    tab.outputRevision.value++
                }
            }
        }
    }

    fun resize(tab: LinuxTerminalTab, columns: Int, rows: Int) {
        renderExecutor.execute {
            tab.screen.resize(columns, rows)
            val rendered = tab.screen.renderAnnotated()
            outputHandler.post {
                if (selectedId == tab.id && tabs.any { it.id == tab.id }) {
                    tab.output.value = rendered
                    tab.outputRevision.value++
                }
            }
        }
    }

    fun add(title: String, session: AutoCloseable, input: OutputStream, interrupt: () -> Unit, resize: (Int, Int) -> Unit = { _, _ -> }, initialOutput: String = "", maxScrollbackLines: Int = 5_000): LinuxTerminalTab {
        val screen = TerminalScreen(maxScrollbackLines = maxScrollbackLines)
        screen.append(initialOutput)
        val ended = AtomicBoolean(false)
        val tab = LinuxTerminalTab(
            id = nextId++,
            title = mutableStateOf(title),
            output = mutableStateOf(screen.renderAnnotated()),
            outputRevision = mutableIntStateOf(0),
            screen = screen,
            send = { text ->
                // Ignore late IME/key events racing with the reader's exit callback.
                if (!ended.get()) {
                    input.write(text.toByteArray(Charsets.UTF_8))
                    input.flush()
                }
            },
            interrupt = { if (!ended.get()) interrupt() },
            session = session,
            resize = { columns, rows -> if (!ended.get()) resize(columns, rows) },
            exited = mutableStateOf(null),
            ended = ended
        )
        tabs.add(tab)
        activeTabIds.add(tab.id)
        selectedId = tab.id
        selectedTabIdForWorker = tab.id
        return tab
    }

    fun markExited(tab: LinuxTerminalTab, code: Int, message: String) {
        // PTY reader callbacks are background-thread callbacks. Never inspect
        // Compose's snapshot-backed tab list or mutate tab state from here.
        if (!activeTabIds.contains(tab.id) || !tab.ended.compareAndSet(false, true)) return
        outputHandler.post {
            if (activeTabIds.contains(tab.id)) tab.exited.value = code
        }
        append(tab, message)
    }

    fun append(tab: LinuxTerminalTab, text: String) {
        if (text.isEmpty() || !activeTabIds.contains(tab.id)) return
        // Reader threads enqueue directly; do not post one main-thread message
        // per PTY read. This prevents a flood of tiny apt/dpkg chunks from
        // starving gestures, input and Compose frames.
        synchronized(outputLock) {
            pendingOutput.getOrPut(tab.id) { StringBuilder() }.append(text)
            if (!scheduledFlushes.add(tab.id)) return
            flushHandler.postDelayed({
                val batch = synchronized(outputLock) {
                    scheduledFlushes.remove(tab.id)
                    pendingOutput.remove(tab.id)?.toString().orEmpty()
                }
                if (batch.isNotEmpty() && activeTabIds.contains(tab.id)) {
                    val shouldStartWorker = synchronized(outputLock) {
                        readyOutput.getOrPut(tab.id) { StringBuilder() }.append(batch)
                        scheduledRenders.add(tab.id)
                    }
                    if (shouldStartWorker) {
                        renderExecutor.execute {
                            // Merge every batch that arrived during this worker turn
                            // before building a Compose snapshot. Rendering the complete
                            // scrollback once per tiny batch would still monopolize the
                            // CPU during package installs, even with a bounded queue.
                            var receivedOutput = false
                            while (activeTabIds.contains(tab.id)) {
                                val nextBatch = synchronized(outputLock) {
                                    val value = readyOutput.remove(tab.id)?.toString().orEmpty()
                                    if (value.isEmpty()) scheduledRenders.remove(tab.id)
                                    value
                                }
                                if (nextBatch.isEmpty()) break
                                tab.screen.append(nextBatch)
                                receivedOutput = true
                            }
                            if (receivedOutput && activeTabIds.contains(tab.id) && selectedTabIdForWorker == tab.id) {
                                val rendered = tab.screen.renderAnnotated()
                                outputHandler.post {
                                    if (selectedId == tab.id && activeTabIds.contains(tab.id)) {
                                        tab.output.value = rendered
                                        tab.outputRevision.value++
                                    }
                                }
                            }
                        }
                    }
                }
            }, OUTPUT_FRAME_INTERVAL_MS)
        }
    }

    fun finish(tab: LinuxTerminalTab) {
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return
        tabs.removeAt(index)
        activeTabIds.remove(tab.id)
        synchronized(outputLock) {
            pendingOutput.remove(tab.id)
            readyOutput.remove(tab.id)
            scheduledFlushes.remove(tab.id)
            scheduledRenders.remove(tab.id)
        }
        runCatching { tab.session.close() }
        if (tabs.isEmpty()) {
            selectedId = 0
            selectedTabIdForWorker = 0
            onLastSessionExited?.invoke()
        } else if (selectedId == tab.id) {
            selectedId = tabs.getOrNull(index - 1)?.id ?: tabs.first().id
            selectedTabIdForWorker = selectedId
        }
    }

    fun closeTab(id: Int) {
        tabs.firstOrNull { it.id == id }?.let(::finish)
    }

}

@Composable
fun LinuxTerminalPanel(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onCloseTerminal: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val paths = remember(context) {
        // Use the same authoritative APK native-library path as Runtime Settings.
        LinuxRuntimePaths(context.filesDir, context.applicationInfo.nativeLibraryDir)
    }
    val terminalPreferences = remember(context) { context.getSharedPreferences(IdeDesignTokens.Preference.FILE, Context.MODE_PRIVATE) }
    var terminalFontSize by remember { mutableIntStateOf(terminalPreferences.getInt(IdeDesignTokens.Preference.TERMINAL_FONT_SIZE, IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE).coerceIn(10, 28)) }
    var scrollbackLines by remember { mutableIntStateOf(terminalPreferences.getInt("terminal_scrollback_lines", 5_000).coerceIn(1_000, 10_000)) }
    var autoShowKeyboard by remember { mutableStateOf(terminalPreferences.getBoolean(IdeDesignTokens.Preference.TERMINAL_AUTO_KEYBOARD, true)) }
    var cursorBlink by remember { mutableStateOf(terminalPreferences.getBoolean(IdeDesignTokens.Preference.TERMINAL_CURSOR_BLINK, true)) }

    DisposableEffect(terminalPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            val changedKey = key ?: return@OnSharedPreferenceChangeListener
            when (changedKey) {
                IdeDesignTokens.Preference.TERMINAL_FONT_SIZE -> terminalFontSize = prefs?.getInt(changedKey, 13)?.coerceIn(10, 28) ?: 13
                "terminal_scrollback_lines" -> scrollbackLines = prefs?.getInt(changedKey, 5_000)?.coerceIn(1_000, 10_000) ?: 5_000
                IdeDesignTokens.Preference.TERMINAL_AUTO_KEYBOARD -> autoShowKeyboard = prefs?.getBoolean(changedKey, true) ?: true
                IdeDesignTokens.Preference.TERMINAL_CURSOR_BLINK -> cursorBlink = prefs?.getBoolean(changedKey, true) ?: true
            }
        }
        terminalPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { terminalPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val connectionMode = terminalPreferences.getString("terminal_connection_mode", "ubuntu") ?: "ubuntu"
    val terminalInputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val sshHostKeyPrefs = remember(context) { context.getSharedPreferences("trusted_ssh_host_keys", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val handler = remember { Handler(Looper.getMainLooper()) }
    var command by remember { mutableStateOf("") }
    var ctrlMode by remember { mutableStateOf(false) }
    var altMode by remember { mutableStateOf(false) }
    var extraKeysEnabled by remember { mutableStateOf(terminalPreferences.getBoolean("terminal_extra_keys_enabled", true)) }
    var showExtraKeysSetup by remember { mutableStateOf(false) }
    var visibleExtraKeys by remember { mutableStateOf(terminalPreferences.getStringSet("terminal_extra_keys", DEFAULT_TERMINAL_EXTRA_KEYS)?.toSet() ?: DEFAULT_TERMINAL_EXTRA_KEYS) }
    var extraKeyOrder by remember {
        mutableStateOf(
            (terminalPreferences.getString("terminal_extra_key_order", TERMINAL_EXTRA_KEY_ORDER.joinToString(",")) ?: TERMINAL_EXTRA_KEY_ORDER.joinToString(","))
                .split(",").filter { it in TERMINAL_EXTRA_KEY_ORDER }
                .let { it + TERMINAL_EXTRA_KEY_ORDER.filterNot(it::contains) }
        )
    }
    var customTerminalKeys by remember {
        mutableStateOf(
            terminalPreferences.getStringSet("terminal_custom_extra_keys", emptySet())?.toList().orEmpty().mapNotNull { entry ->
                val parts = entry.split("::", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotEmpty()) parts[0] to parts[1] else null
            }.sortedBy { it.first.lowercase() }
        )
    }
    var newCustomKeyLabel by remember { mutableStateOf("") }
    var newCustomKeySequence by remember { mutableStateOf("") }
    var sessionMenuId by remember { mutableIntStateOf(0) }
    var renameTabId by remember { mutableIntStateOf(0) }
    var renameValue by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var host by remember { mutableStateOf(terminalPreferences.getString("terminal_ssh_host", "").orEmpty()) }
    var port by remember { mutableStateOf(terminalPreferences.getInt("terminal_ssh_port", 22).toString()) }
    var username by remember { mutableStateOf(terminalPreferences.getString("terminal_ssh_user", "").orEmpty()) }
    var password by remember { mutableStateOf(TerminalConnectionConfig.sshPassword) }
    var privateKeyBytes by remember { mutableStateOf(TerminalConnectionConfig.sshPrivateKey) }
    var privateKeyPassphrase by remember { mutableStateOf(TerminalConnectionConfig.sshPrivateKeyPassphrase) }
    var expectedFingerprint by remember { mutableStateOf("") }
    var fingerprintConfirmation by remember { mutableStateOf<String?>(null) }

    DisposableEffect(onCloseTerminal) {
        LinuxTerminalSessions.onLastSessionExited = onCloseTerminal
        onDispose {
            if (LinuxTerminalSessions.onLastSessionExited === onCloseTerminal) LinuxTerminalSessions.onLastSessionExited = null
        }
    }

    fun connectSsh(expected: String?) {
        val sshHost = try {
            SshHost(host.trim(), port.toIntOrNull() ?: 22, username.trim(), password = password.ifBlank { null }, privateKey = privateKeyBytes, privateKeyPassphrase = privateKeyPassphrase.ifBlank { null }, expectedSha256Fingerprint = expected ?: sshHostKeyPrefs.getString("${host.trim()}:${port.toIntOrNull() ?: 22}", null))
        } catch (e: Exception) {
            error = terminalUserMessage(e.message, "Please check your SSH connection settings.")
            return
        }
        // Persist only non-secret connection coordinates; passwords and private keys stay in memory.
        terminalPreferences.edit()
            .putString("terminal_ssh_host", sshHost.host)
            .putInt("terminal_ssh_port", sshHost.port)
            .putString("terminal_ssh_user", sshHost.username)
            .apply()
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(SshShellSession.connect(sshHost)) }
                catch (e: Exception) { Result.failure<SshShellSession>(e) }
            }
            busy = false
            result.onSuccess { session ->
                val title = "SSH ${sshHost.username}@${sshHost.host}"
                DeveloperLog.info(context, "SSH", "Connected to configured SSH endpoint")
                val tab = LinuxTerminalSessions.add(title, session, session.input, interrupt = { runCatching { session.input.write(byteArrayOf(3)); session.input.flush() } }, resize = session::resize, initialOutput = "Connected to ${sshHost.host}:\n", maxScrollbackLines = scrollbackLines)
                session.readOutput(
                    onChunk = { chunk -> LinuxTerminalSessions.append(tab, chunk) },
                    onExit = { code -> handler.post {
                        LinuxTerminalSessions.closeTab(tab.id)
                    } }
                )
                password = ""
                TerminalConnectionConfig.sshPassword = ""
                privateKeyBytes = null
                TerminalConnectionConfig.sshPrivateKey = null
                TerminalConnectionConfig.sshPrivateKeyPassphrase = ""
                privateKeyPassphrase = ""
            }.onFailure { e ->
                if (e is HostKeyConfirmationRequired) {
                    fingerprintConfirmation = e.fingerprint
                    expectedFingerprint = e.fingerprint
                } else {
                    DeveloperLog.warn(context, "SSH", "SSH connection failed", e)
                    error = terminalUserMessage(e.message, "Could not connect to the SSH server.")
                }
            }
        }
    }

    fun startLocalSession() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    paths.ensureDirectories()
                    val prefs = context.getSharedPreferences("linux_runtime_validation", Context.MODE_PRIVATE)
                    val checkedAt = prefs.getLong("checked_at", 0L)
                    val cachedReady = prefs.getBoolean("ready", false)
                    val cacheFresh = cachedReady && checkedAt > 0L &&
                        System.currentTimeMillis() - checkedAt in 0L..120_000L

                    if (!cacheFresh) {
                        val validationMessage = when {
                            !paths.hasRootfs() -> "Ubuntu rootfs is not installed or incomplete."
                            else -> {
                                ProotDownloader(paths).downloadAndInstall()
                                val smoke = LinuxRuntimeSmokeTest(paths).run()
                                if (!smoke.success) {
                                    DeveloperLog.error(
                                        context,
                                        "Ubuntu",
                                        "Runtime smoke test failed: ${smoke.error ?: smoke.output}; ${paths.rootfsDiagnostic()}"
                                    )
                                    terminalUserMessage(smoke.error ?: smoke.output, "Ubuntu could not start. Please try again.")
                                } else {
                                    DeveloperLog.info(context, "Ubuntu", "Runtime smoke test passed")
                                    "Ubuntu runtime ready."
                                }
                            }
                        }
                        val ready = validationMessage == "Ubuntu runtime ready."
                        prefs.edit()
                            .putBoolean("ready", ready)
                            .putString("message", validationMessage)
                            .putLong("checked_at", System.currentTimeMillis())
                            .apply()
                        check(ready) { validationMessage }
                    } else {
                        // Re-check the APK-owned executables and rootfs markers even when
                        // reusing a recent successful shell smoke test.
                        ProotDownloader(paths).downloadAndInstall()
                        check(paths.hasRootfs() && paths.hasProot()) {
                            "Ubuntu runtime files changed after the last successful validation. Reopen Runtime & Bootstrap. ${paths.rootfsDiagnostic()}"
                        }
                    }
                    LocalLinuxSession.start(paths)
                }
            }
            busy = false
            result.onSuccess { session ->
                DeveloperLog.info(context, "Ubuntu", "Local Ubuntu terminal session started")
                val tab = LinuxTerminalSessions.add("Ubuntu " + (LinuxTerminalSessions.tabs.size + 1), session, session.input, interrupt = { session.interrupt() }, resize = session::resize, initialOutput = "", maxScrollbackLines = scrollbackLines)
                session.readOutput(
                    onChunk = { chunk -> LinuxTerminalSessions.append(tab, chunk) },
                    onExit = { code -> handler.post {
                        LinuxTerminalSessions.closeTab(tab.id)
                    } }
                )
            }.onFailure { failure ->
                DeveloperLog.error(context, "Ubuntu", "Local Ubuntu terminal session failed to start", failure)
                error = terminalUserMessage(failure.message, "Ubuntu could not start. Please check the Ubuntu setup and try again.")
            }
        }
    }

    LaunchedEffect(connectionMode) {
        // Reopening the terminal must reuse the process kept in the session registry.
        if (LinuxTerminalSessions.tabs.isNotEmpty()) return@LaunchedEffect
        if (connectionMode == "ssh") {
            when {
                host.isBlank() || username.isBlank() -> error = "Configure SSH host and username in Settings → Terminal."
                password.isBlank() && privateKeyBytes == null -> error = "Add an SSH password or private key in Settings → Terminal."
                else -> connectSsh(null)
            }
        } else {
            startLocalSession()
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        if (busy) Text("Starting session…", color = Color(0xFF9AA7B5), modifier = Modifier.padding(8.dp))

        if (LinuxTerminalSessions.tabs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF0F151D)).padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LinuxTerminalSessions.tabs.forEach { tab ->
                    Row {
                        Row(
                            Modifier.background(if (tab.id == LinuxTerminalSessions.selectedId) Color(0xFF1D3548) else Color(0xFF111820), RoundedCornerShape(6.dp))
                                .combinedClickable(
                                    onClick = { LinuxTerminalSessions.select(tab.id) },
                                    onLongClick = { sessionMenuId = tab.id }
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(tab.title.value, color = if (tab.id == LinuxTerminalSessions.selectedId) Color(0xFFE6EDF3) else Color(0xFF9AA7B5), maxLines = 1, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = sessionMenuId == tab.id, onDismissRequest = { sessionMenuId = 0 }) {
                            DropdownMenuItem(text = { Text("Rename session") }, onClick = {
                                renameTabId = tab.id
                                renameValue = tab.title.value
                                sessionMenuId = 0
                            })
                            DropdownMenuItem(text = { Text("Close session") }, onClick = {
                                sessionMenuId = 0
                                LinuxTerminalSessions.closeTab(tab.id)
                            })
                        }
                    }
                }
                }
                Text("+", modifier = Modifier.background(Color(0xFF263746), RoundedCornerShape(6.dp)).clickable {
                    if (connectionMode == "ssh") onOpenSettings() else startLocalSession()
                }.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontSize = 16.sp)
            }
        }

        val active = LinuxTerminalSessions.tabs.firstOrNull { it.id == LinuxTerminalSessions.selectedId }
        LaunchedEffect(active?.id, autoShowKeyboard) {
            if (active != null && autoShowKeyboard && active.exited.value == null) {
                kotlinx.coroutines.delay(180)
                terminalInputFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
        if (active == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux terminal", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("No active terminal session. Choose the connection type and start a session from Terminal Settings.", color = Color(0xFF9AA7B5), fontSize = 12.sp)
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            }
        } else {
            // Keep the cursor steady. Re-rendering the entire scrollback every
            // 530ms caused visible text flicker; Termux blinks only the cursor
            // in its Canvas renderer, not the terminal text buffer.
            var cursorBlinkTick by remember(active.id) { mutableIntStateOf(0) }
            LaunchedEffect(active.id, cursorBlink) {
                active.screen.setCursorBlinkPhase(true)
                if (cursorBlink) {
                    while (true) {
                        delay(530)
                        if (active.screen.advanceCursorBlinkPhase()) cursorBlinkTick++
                    }
                }
            }
            // Each terminal tab owns its own scroll position.
            val outputScrollState = remember(active.id) { androidx.compose.foundation.ScrollState(0) }
            val outputHorizontalScrollState = remember(active.id) { androidx.compose.foundation.ScrollState(0) }
            val cursorTextLayout = remember(active.id) { mutableStateOf<TextLayoutResult?>(null) }
            var terminalVisualScale by remember(active.id) { mutableFloatStateOf(1f) }
            val terminalTextMeasurer = rememberTextMeasurer()
            // Change the actual glyph/cell layout size. A graphics-only scale leaves
            // the text layout and horizontal scroll extent at the old width.
            val displayFontSize = (terminalFontSize * terminalVisualScale).coerceIn(7f, 56f)
            // Visual cell width follows pinch zoom for cursor placement and scrolling.
            val cursorCellWidth = terminalTextMeasurer.measure(
                AnnotatedString("M"),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = displayFontSize.sp)
            ).size.width.toFloat().coerceAtLeast(1f)
            // PTY geometry is deliberately independent from pinch zoom. Only the
            // unscaled configured font and actual viewport dimensions determine rows/columns.
            val ptyCellWidth = terminalTextMeasurer.measure(
                AnnotatedString("M"),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = terminalFontSize.sp)
            ).size.width.toFloat().coerceAtLeast(1f)
            var terminalViewportSize by remember(active.id) { mutableStateOf(IntSize.Zero) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            LaunchedEffect(active.id, terminalViewportSize, terminalFontSize) {
                if (terminalViewportSize.width <= 0 || terminalViewportSize.height <= 0) return@LaunchedEffect
                // BottomSheet drag/expand changes the actual PTY grid. Debounce the
                // stream of intermediate pixel sizes so the shell is not resized
                // dozens of times during one gesture.
                delay(90)
                val lineHeightPx = with(density) { (terminalFontSize * 1.28f).sp.toPx() }
                val columns = (terminalViewportSize.width / ptyCellWidth).toInt().coerceIn(20, 300)
                val rows = (terminalViewportSize.height / lineHeightPx.coerceAtLeast(1f)).toInt().coerceIn(5, 120)
                if (columns != active.lastPtyColumns || rows != active.lastPtyRows) {
                    active.resize(columns, rows)
                    LinuxTerminalSessions.resize(active, columns, rows)
                    active.lastPtyColumns = columns
                    active.lastPtyRows = rows
                }
            }
            LaunchedEffect(active.id, active.outputRevision.value) {
                val previousScrollValue = outputScrollState.value
                val wasFollowingOutput = outputScrollState.maxValue - previousScrollValue <= 48
                if (wasFollowingOutput) {
                    delay(16)
                    // Do not yank the viewport back to the bottom if the user
                    // starts scrolling into history while new output arrives.
                    if (outputScrollState.value == previousScrollValue &&
                        !outputScrollState.isScrollInProgress
                    ) {
                        outputScrollState.scrollTo(outputScrollState.maxValue)
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().background(Color.Black)
            ) {
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .background(Color.Black)
                        // Scroll the viewport container rather than the selectable
                        // Text node; this keeps one-finger history scrolling reliable
                        // while text selection and horizontal panning remain available.
                        .verticalScroll(outputScrollState, flingBehavior = ScrollableDefaults.flingBehavior())
                        .onSizeChanged { terminalViewportSize = it }
                        .pointerInput(active.id) {
                            // The whole terminal viewport is an input surface, including
                            // blank rows and the unused area below the current prompt.
                            detectTapGestures {
                                terminalInputFocusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        }
                        .pointerInput(active.id) {
                            // Unlike detectTransformGestures, this recognizer does
                            // not consume one-finger drags. Scrolling therefore stays
                            // available while pinch zoom is handled only for 2+ fingers.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } >= 2) {
                                        val zoom = event.calculateZoom()
                                        if (zoom.isFinite() && zoom > 0f && kotlin.math.abs(zoom - 1f) > 0.001f) {
                                            terminalVisualScale = (terminalVisualScale * zoom).coerceIn(0.65f, 2.0f)
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    SelectionContainer {
                        Text(
                            active.output.value,
                            color = Color(0xFFE5E5E5),
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                            fontSize = displayFontSize.sp,
                            lineHeight = (displayFontSize * 1.28f).sp,
                            onTextLayout = { cursorTextLayout.value = it },
                            modifier = Modifier
                                .horizontalScroll(outputHorizontalScrollState)
                                .fillMaxWidth()
                        )
                    }
                    // Draw the terminal cursor in a dedicated Canvas layer. The
                    // terminal text itself contains only shell output, never a cursor glyph.
                    Canvas(Modifier.matchParentSize()) {
                        // Reading this Compose state invalidates the Canvas on every blink phase.
                        if (cursorBlinkTick >= 0 && active.screen.isCursorDrawn()) {
                            val layout = cursorTextLayout.value
                            if (layout != null) {
                                // Ask Compose for the caret rectangle at the terminal's
                                // rendered insertion offset. Multiplying the terminal column
                                // by an estimated "M" width drifts after wide glyphs, combining
                                // sequences and emoji; TextLayoutResult already knows the
                                // exact shaped-text position.
                                val cursorOffset = active.screen.cursorTextOffset()
                                    .coerceIn(0, layout.layoutInput.text.length)
                                val caret = layout.getCursorRect(cursorOffset)
                                val left = caret.left - outputHorizontalScrollState.value
                                val top = caret.top - outputScrollState.value
                                val bottom = caret.bottom - outputScrollState.value
                                val shape = active.screen.cursorShapeCode()
                                val cursorHeight = (bottom - top).coerceAtLeast(2f)
                                // A terminal block cursor is opaque. When a glyph exists
                                // under the insertion point, redraw that complete grapheme
                                // in a contrasting foreground so the character remains legible.
                                var blockWidth = cursorCellWidth.coerceAtLeast(2f)
                                var blockGlyph: androidx.compose.ui.text.TextLayoutResult? = null
                                if (shape in 0..2 && cursorOffset < layout.layoutInput.text.length) {
                                    val source = layout.layoutInput.text.text
                                    val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
                                    breaker.setText(source)
                                    val next = breaker.following(cursorOffset)
                                    if (next != BreakIterator.DONE) {
                                        val cluster = source.substring(cursorOffset, next)
                                        if (cluster.isNotEmpty() && cluster.none { it == '\n' || it == '\r' }) {
                                            blockGlyph = terminalTextMeasurer.measure(
                                                AnnotatedString(cluster),
                                                style = TextStyle(
                                                    color = Color(0xFF0B0F14),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = displayFontSize.sp,
                                                    fontWeight = FontWeight.Normal
                                                )
                                            )
                                            blockWidth = maxOf(blockWidth, blockGlyph.size.width.toFloat())
                                        }
                                    }
                                }
                                val right = left + blockWidth
                                if (right >= 0f && left <= size.width && bottom >= 0f && top <= size.height) {
                                    when (shape) {
                                        0, 1, 2 -> {
                                            drawRect(
                                                color = Color(0xFF8AB4F8),
                                                topLeft = Offset(left, top),
                                                size = androidx.compose.ui.geometry.Size(
                                                    blockWidth,
                                                    cursorHeight
                                                )
                                            )
                                            blockGlyph?.let { glyph ->
                                                drawText(glyph, topLeft = Offset(left, top))
                                            }
                                        }
                                        3, 4 -> drawLine(
                                            color = Color(0xFF8AB4F8),
                                            start = Offset(left, bottom - 2f),
                                            end = Offset(right, bottom - 2f),
                                            strokeWidth = 2f,
                                            cap = StrokeCap.Square
                                        )
                                        5, 6 -> drawLine(
                                            color = Color(0xFF8AB4F8),
                                            start = Offset(left + 1f, top),
                                            end = Offset(left + 1f, bottom),
                                            strokeWidth = 2f,
                                            cap = StrokeCap.Square
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = command,
                    onValueChange = { updated ->
                        val previous = command
                        if (active.exited.value == null) {
                            val commonPrefix = previous.commonPrefixWith(updated)
                            val removed = previous.length - commonPrefix.length
                            val added = updated.substring(commonPrefix.length)
                            try {
                                repeat(removed) { active.send("\u007f") }
                                if (added.isNotEmpty()) {
                                if (ctrlMode && added.length == 1) {
                                    active.send((added[0].uppercaseChar().code and 0x1f).toChar().toString())
                                    ctrlMode = false
                                } else {
                                    if (altMode) {
                                        active.send("\u001b" + added)
                                        altMode = false
                                    } else active.send(added)
                                }
                            }
                            } catch (e: Exception) { error = terminalUserMessage(e.message, "Could not send input to the terminal. Please try again.") }
                        }
                        command = updated
                    },
                    modifier = Modifier.size(1.dp).focusRequester(terminalInputFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || active.exited.value != null) return@onPreviewKeyEvent false
                            if (event.isCtrlPressed) {
                                val control = when (event.key) {
                                    androidx.compose.ui.input.key.Key.A -> "\u0001" // beginning of line
                                    androidx.compose.ui.input.key.Key.C -> "\u0003" // interrupt
                                    androidx.compose.ui.input.key.Key.D -> "\u0004" // EOF
                                    androidx.compose.ui.input.key.Key.E -> "\u0005" // end of line
                                    androidx.compose.ui.input.key.Key.K -> "\u000b" // kill to end of line
                                    androidx.compose.ui.input.key.Key.L -> "\u000c" // clear screen
                                    androidx.compose.ui.input.key.Key.R -> "\u0012" // reverse history search
                                    androidx.compose.ui.input.key.Key.U -> "\u0015" // kill to beginning of line
                                    androidx.compose.ui.input.key.Key.W -> "\u0017" // erase previous word
                                    androidx.compose.ui.input.key.Key.Z -> "\u001a" // suspend foreground job
                                    else -> null
                                }
                                if (control != null) {
                                    try { active.send(control) } catch (e: Exception) { error = terminalUserMessage(e.message, "Could not send input to the terminal. Please try again.") }
                                    return@onPreviewKeyEvent true
                                }
                            }
                            when (event.key) {
                                androidx.compose.ui.input.key.Key.DirectionUp -> { active.send("\u001b[A"); true }
                                androidx.compose.ui.input.key.Key.DirectionDown -> { active.send("\u001b[B"); true }
                                androidx.compose.ui.input.key.Key.DirectionRight -> { active.send("\u001b[C"); true }
                                androidx.compose.ui.input.key.Key.DirectionLeft -> { active.send("\u001b[D"); true }
                                androidx.compose.ui.input.key.Key.MoveHome -> { active.send("\u001b[H"); true }
                                androidx.compose.ui.input.key.Key.MoveEnd -> { active.send("\u001b[F"); true }
                                androidx.compose.ui.input.key.Key.PageUp -> { active.send("\u001b[5~"); true }
                                androidx.compose.ui.input.key.Key.PageDown -> { active.send("\u001b[6~"); true }
                                androidx.compose.ui.input.key.Key.Delete -> { active.send("\u001b[3~"); true }
                                androidx.compose.ui.input.key.Key.Tab -> { active.send("\t"); true }
                                androidx.compose.ui.input.key.Key.Escape -> { active.send("\u001b"); true }
                                androidx.compose.ui.input.key.Key.Backspace -> { active.send("\u007f"); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        try { active.send("\r") } catch (e: Exception) { error = terminalUserMessage(e.message, "Could not send input to the terminal. Please try again.") }
                        command = ""
                    }),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent, fontFamily = FontFamily.Monospace, fontSize = terminalFontSize.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                    decorationBox = { inner -> inner() }
                )
            Column(Modifier.fillMaxWidth().background(Color(0xFF101820)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                if (extraKeysEnabled) {
                    val activeKeys = extraKeyOrder.filter { it in visibleExtraKeys }
                    val firstRowCount = (activeKeys.size + 1) / 2
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        activeKeys.take(firstRowCount).forEach { key ->
                    TextButton(enabled = active.exited.value == null, onClick = {
                        val sequence = when (key) {
                            "ESC" -> "\u001b"
                            "TAB" -> "\t"
                            "HOME" -> "\u001b[H"
                            "END" -> "\u001b[F"
                            "UP", "DOWN", "LEFT", "RIGHT" -> active.screen.cursorKeySequence(key)
                            "PGUP" -> "\u001b[5~"
                            "PGDN" -> "\u001b[6~"
                            else -> key
                        }
                        when (key) {
                            "CTRL" -> ctrlMode = !ctrlMode
                            "ALT" -> altMode = !altMode
                            else -> runCatching { active.send(sequence) }.onFailure { error = terminalUserMessage(it.message, "Could not send input to the terminal. Please try again.") }
                        }
                        terminalInputFocusRequester.requestFocus()
                    }) {
                        Text(when (key) { "PGUP" -> "PgUp"; "PGDN" -> "PgDn"; else -> key },
                            color = when (key) {
                                "CTRL" -> if (ctrlMode) Color(0xFF80C7FF) else Color(0xFFD5E3F0)
                                "ALT" -> if (altMode) Color(0xFF80C7FF) else Color(0xFFD5E3F0)
                                else -> Color(0xFFD5E3F0)
                            }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        activeKeys.drop(firstRowCount).forEach { key ->
                    TextButton(enabled = active.exited.value == null, onClick = {
                        val sequence = when (key) {
                            "ESC" -> "\u001b"; "TAB" -> "\t"; "HOME" -> "\u001b[H"; "END" -> "\u001b[F"
                            "UP" -> "\u001b[A"; "DOWN" -> "\u001b[B"; "LEFT" -> "\u001b[D"; "RIGHT" -> "\u001b[C"
                            "PGUP" -> "\u001b[5~"; "PGDN" -> "\u001b[6~"; else -> key
                        }
                        when (key) {
                            "CTRL" -> ctrlMode = !ctrlMode
                            "ALT" -> altMode = !altMode
                            else -> runCatching { active.send(sequence) }.onFailure { error = terminalUserMessage(it.message, "Could not send input to the terminal. Please try again.") }
                        }
                        terminalInputFocusRequester.requestFocus()
                    }) {
                        Text(when (key) { "PGUP" -> "PgUp"; "PGDN" -> "PgDn"; else -> key },
                            color = when (key) {
                                "CTRL" -> if (ctrlMode) Color(0xFF80C7FF) else Color(0xFFD5E3F0)
                                "ALT" -> if (altMode) Color(0xFF80C7FF) else Color(0xFFD5E3F0)
                                else -> Color(0xFFD5E3F0)
                            }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                        customTerminalKeys.forEach { (label, sequence) ->
                            TextButton(enabled = active.exited.value == null, onClick = {
                                val payload = when (sequence.uppercase()) {
                                    "CTRL+C" -> 3.toChar().toString(); "CTRL+D" -> 4.toChar().toString()
                                    "CTRL+Z" -> 26.toChar().toString(); "CTRL+L" -> 12.toChar().toString()
                                    "ESC" -> 27.toChar().toString(); "TAB" -> 9.toChar().toString()
                                    "ENTER" -> 13.toChar().toString(); else -> sequence
                                }
                                runCatching { active.send(payload) }.onFailure { error = terminalUserMessage(it.message, "Could not send this key to the terminal.") }
                                terminalInputFocusRequester.requestFocus()
                            }) { Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                        TextButton(onClick = {
                            runCatching { clipboardManager.setText(active.output.value) }
                                .onFailure { error = terminalUserMessage(it.message, "Could not copy terminal output.") }
                        }) { Text("Copy all", fontSize = 11.sp) }
                        TextButton(enabled = active.exited.value == null, onClick = {
                            val pasted = clipboardManager.getText()?.text.orEmpty()
                            if (pasted.isNotEmpty()) runCatching { active.send(active.screen.pasteSequence(pasted)) }.onFailure { error = terminalUserMessage(it.message, "Could not paste into the terminal.") }
                            terminalInputFocusRequester.requestFocus()
                        }) { Text("Paste", fontSize = 11.sp) }
                        TextButton(enabled = active.exited.value == null, onClick = {
                            runCatching { active.send("\u000c") }.onFailure { error = terminalUserMessage(it.message, "Could not clear the terminal.") }
                        }) { Text("Clear", fontSize = 11.sp) }
                        TextButton(enabled = active.exited.value == null, onClick = {
                            terminalInputFocusRequester.requestFocus()
                            keyboardController?.show()
                        }) { Text("⌨", fontSize = 13.sp) }
                        TextButton(onClick = { showExtraKeysSetup = true }) { Text("⚙", fontSize = 13.sp) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = {
                            extraKeysEnabled = true
                            terminalPreferences.edit().putBoolean("terminal_extra_keys_enabled", true).apply()
                        }) { Text("Enable extra keys", fontSize = 11.sp) }
                        TextButton(onClick = { showExtraKeysSetup = true }) { Text("⚙", fontSize = 13.sp) }
                    }
                }
            }

            }
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
        }
    }

    if (showExtraKeysSetup) {
        val presets = mapOf(
            "Termux default" to setOf("ESC", "TAB", "CTRL", "ALT", "HOME", "UP", "END", "PGUP", "LEFT", "DOWN", "RIGHT", "PGDN"),
            "Vim / Neovim" to setOf("ESC", "TAB", "CTRL", "ALT", "HOME", "UP", "END", "LEFT", "DOWN", "RIGHT"),
            "Navigation" to setOf("HOME", "UP", "END", "PGUP", "LEFT", "DOWN", "RIGHT", "PGDN"),
            "Minimal" to setOf("ESC", "TAB", "CTRL", "ALT")
        )
        AlertDialog(
            onDismissRequest = { showExtraKeysSetup = false },
            title = { Text("Extra keys setup") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Choose a Termux-style preset or select the keys you need. Changes are saved automatically.", fontSize = 12.sp, color = Color(0xFF9AA7B5))
                    presets.forEach { (name, keys) ->
                        OutlinedButton(
                            onClick = {
                                visibleExtraKeys = keys
                                terminalPreferences.edit().putStringSet("terminal_extra_keys", keys).apply()
                                showExtraKeysSetup = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                    Text("Custom keys", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newCustomKeyLabel,
                            onValueChange = { newCustomKeyLabel = it.take(10) },
                            label = { Text("Label") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = newCustomKeySequence,
                            onValueChange = { newCustomKeySequence = it.take(32) },
                            label = { Text("Sequence") },
                            singleLine = true,
                            modifier = Modifier.weight(1.5f)
                        )
                    }
                    Button(
                        enabled = newCustomKeyLabel.isNotBlank() && newCustomKeySequence.isNotEmpty(),
                        onClick = {
                            val updated = (customTerminalKeys + (newCustomKeyLabel.trim() to newCustomKeySequence)).distinctBy { it.first.lowercase() }
                            customTerminalKeys = updated
                            terminalPreferences.edit().putStringSet("terminal_custom_extra_keys", updated.map { "${it.first}::${it.second}" }.toSet()).apply()
                            newCustomKeyLabel = ""
                            newCustomKeySequence = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add custom key") }
                    customTerminalKeys.forEach { (label, sequence) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("$label  →  $sequence", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            TextButton(onClick = {
                                val updated = customTerminalKeys.filterNot { it.first == label }
                                customTerminalKeys = updated
                                terminalPreferences.edit().putStringSet("terminal_custom_extra_keys", updated.map { "${it.first}::${it.second}" }.toSet()).apply()
                            }) { Text("Remove") }
                        }
                    }
                    TERMINAL_EXTRA_KEY_ORDER.forEach { key ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val updated = visibleExtraKeys.toMutableSet()
                                if (key in updated) updated.remove(key) else updated.add(key)
                                visibleExtraKeys = updated
                                terminalPreferences.edit().putStringSet("terminal_extra_keys", updated).apply()
                            }.padding(vertical = 2.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = key in visibleExtraKeys,
                                onCheckedChange = { checked ->
                                    val updated = visibleExtraKeys.toMutableSet()
                                    if (checked) updated.add(key) else updated.remove(key)
                                    visibleExtraKeys = updated
                                    terminalPreferences.edit().putStringSet("terminal_extra_keys", updated).apply()
                                }
                            )
                            Text(key, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            val index = extraKeyOrder.indexOf(key)
                            TextButton(enabled = index > 0, onClick = {
                                val reordered = extraKeyOrder.toMutableList()
                                val item = reordered.removeAt(index)
                                reordered.add(index - 1, item)
                                extraKeyOrder = reordered
                                terminalPreferences.edit().putString("terminal_extra_key_order", reordered.joinToString(",")).apply()
                            }) { Text("↑") }
                            TextButton(enabled = index < extraKeyOrder.lastIndex, onClick = {
                                val reordered = extraKeyOrder.toMutableList()
                                val item = reordered.removeAt(index)
                                reordered.add(index + 1, item)
                                extraKeyOrder = reordered
                                terminalPreferences.edit().putString("terminal_extra_key_order", reordered.joinToString(",")).apply()
                            }) { Text("↓") }
                        }
                    }
                    TextButton(onClick = {
                        visibleExtraKeys = DEFAULT_TERMINAL_EXTRA_KEYS
                        extraKeyOrder = TERMINAL_EXTRA_KEY_ORDER
                        terminalPreferences.edit().putString("terminal_extra_key_order", TERMINAL_EXTRA_KEY_ORDER.joinToString(","))
                            .putStringSet("terminal_extra_keys", DEFAULT_TERMINAL_EXTRA_KEYS).apply()
                        terminalPreferences.edit().putStringSet("terminal_extra_keys", DEFAULT_TERMINAL_EXTRA_KEYS).apply()
                        showExtraKeysSetup = false
                    }) { Text("Reset to default") }
                }
            },
            confirmButton = { TextButton(onClick = { showExtraKeysSetup = false }) { Text("Done") } }
        )
    }

    if (renameTabId != 0) {
        AlertDialog(
            onDismissRequest = { renameTabId = 0 },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(48) },
                    singleLine = true,
                    label = { Text("Session name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LinuxTerminalSessions.tabs.firstOrNull { it.id == renameTabId }?.title?.value =
                        renameValue.trim().ifBlank { "Terminal" }
                    renameTabId = 0
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTabId = 0 }) { Text("Cancel") } }
        )
    }

    fingerprintConfirmation?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = { fingerprintConfirmation = null; expectedFingerprint = "" },
            title = { Text("Verify SSH server") },
            text = {
                Text("First connection to $host presented this SHA-256 host key:\n\n$fingerprint\n\nOnly trust it if it matches the fingerprint provided by your server administrator. No shell commands have been sent.", color = Color(0xFFE8EDF5))
            },
            confirmButton = {
                Button(onClick = {
                    fingerprintConfirmation = null
                    sshHostKeyPrefs.edit().putString("${host.trim()}:${port.toIntOrNull() ?: 22}", fingerprint).apply()
                    expectedFingerprint = fingerprint
                    connectSsh(fingerprint)
                }) { Text("Trust fingerprint and connect") }
            },
            dismissButton = {
                TextButton(onClick = { fingerprintConfirmation = null; expectedFingerprint = "" }) { Text("Cancel") }
            }
        )
    }
}



/** Keeps implementation details out of normal terminal UI error banners. */
private fun terminalUserMessage(details: String?, fallback: String): String {
    val message = details.orEmpty().lowercase()
    return when {
        message.contains("host key changed") ->
            "The SSH server identity has changed. Do not connect until you have verified the new server fingerprint."
        message.contains("confirm the ssh server host key") ->
            "Verify the SSH server fingerprint before connecting."
        message.contains("unknownhost") || message.contains("unable to resolve") ||
            message.contains("connection refused") || message.contains("no route to host") ||
            message.contains("network is unreachable") || message.contains("timed out") ->
            "Could not reach the SSH server. Check the host, port, and internet connection."
        message.contains("authentication") || message.contains("auth fail") ||
            message.contains("permission denied") || message.contains("publickey") ->
            "SSH login was rejected. Check your username, password, or private key."
        message.contains("invalid ssh") || message.contains("invalid port") ->
            "The SSH settings are invalid. Check the server address and port."
        message.contains("rootfs") && (message.contains("missing") || message.contains("incomplete")) ->
            "Ubuntu is not installed completely. Open Runtime Setup and install Ubuntu."
        message.contains("proot") || message.contains("loader") || message.contains("eacces") ->
            "The terminal component could not start. Please update or reinstall the app."
        message.contains("apt-get check") || message.contains("dpkg") ->
            "Ubuntu's package database needs repair. Run: sudo apt --fix-broken install"
        message.contains("no space") || message.contains("enospc") ->
            "There is not enough free storage. Free up some space and try again."
        else -> fallback
    }
}
