package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.TypedValue
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import com.techwithkaushik.cppide.integration.termuxruntime.BootstrapSmokeTest
import com.techwithkaushik.cppide.integration.termuxruntime.EmbeddedTermuxRuntime
import com.techwithkaushik.cppide.integration.termuxruntime.RuntimeLayout
import com.techwithkaushik.cppide.integration.termuxruntime.AndroidRuntimeLauncher
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class RuntimeStatus(
    val ready: Boolean,
    val message: String
)

/**
 * Hosts the upstream Termux terminal emulator in the IDE's bottom panel.
 *
 * The runtime is checked on an IO dispatcher before creating a terminal session.
 * A shell file existing on disk is not enough: it must successfully execute a
 * fixed smoke-test command. Android's /system/bin/sh is never used as fallback.
 */
@Composable
fun TermuxTerminalPanel(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onCloseTerminal: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val layout = remember(appContext) { RuntimeLayout.fromAppFilesDirectory(appContext.filesDir) }
    var runtimeRefreshToken by remember(appContext) { mutableIntStateOf(0) }
    val runtimeStatus = produceState(
        initialValue = RuntimeStartupCheckCache.get(layout.prefix.absolutePath)
            ?: RuntimeStatus(false, "Checking the embedded runtime…"),
        key1 = appContext,
        key2 = runtimeRefreshToken
    ) {
        val prefixKey = layout.prefix.absolutePath
        if (runtimeRefreshToken > 0) RuntimeStartupCheckCache.invalidate(prefixKey)
        value = RuntimeStartupCheckCache.get(prefixKey) ?: withContext(Dispatchers.IO) {
            try {
                val runtime = EmbeddedTermuxRuntime(appContext.filesDir)
                if (!runtime.hasBootstrapLayout()) {
                    RuntimeStatus(
                        ready = false,
                        message = "No runtime is installed. Open Settings → Runtime & Bootstrap and select a bootstrap ZIP."
                    )
                } else {
                    // This potentially expensive repair scan and executable smoke
                    // test run once per app process, not every time the bottom
                    // sheet is opened. A successful result is cached; failed checks
                    // remain retryable, and the explicit Retry button invalidates it.
                    runtime.repairInstalledRuntimePaths()
                    val smokeTest = BootstrapSmokeTest(runtime).run()
                    if (smokeTest.success) RuntimeStatus(true, "")
                    else {
                        val detail = listOfNotNull(
                            smokeTest.failureReason,
                            smokeTest.stderr.trim().takeIf { it.isNotBlank() }?.let { "stderr: ${it.take(240)}" }
                        ).joinToString(" — ")
                        RuntimeStatus(
                            ready = false,
                            message = "Runtime validation failed${if (detail.isBlank()) "." else ": $detail"} Open Settings → Runtime & Bootstrap to reinstall or repair it."
                        )
                    }
                }
            } catch (error: Exception) {
                RuntimeStatus(
                    ready = false,
                    message = "Runtime check failed: ${error.message ?: error.javaClass.simpleName}"
                )
            }.also { status ->
                if (status.ready) RuntimeStartupCheckCache.store(prefixKey, status)
            }
        }
    }.value

    val shell = remember(layout, runtimeStatus.ready) {
        if (!runtimeStatus.ready) null
        else listOf(File(layout.prefix, "bin/bash"), File(layout.prefix, "bin/sh"))
            .firstOrNull { it.isFile && it.canExecute() }
    }
    val shellArguments = remember(shell) {
        if (shell?.name == "bash") arrayOf("--login") else emptyArray()
    }
    val terminalPreferences = remember(appContext) {
        appContext.getSharedPreferences(IdeDesignTokens.Preference.FILE, Context.MODE_PRIVATE)
    }
    var terminalFontSizeSp by remember(appContext) {
        mutableIntStateOf(
            terminalPreferences.getInt(
                IdeDesignTokens.Preference.TERMINAL_FONT_SIZE,
                IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE
            )
        )
    }
    var autoShowKeyboard by remember(appContext) {
        mutableStateOf(terminalPreferences.getBoolean(IdeDesignTokens.Preference.TERMINAL_AUTO_KEYBOARD, true))
    }
    var cursorBlinkEnabled by remember(appContext) {
        mutableStateOf(terminalPreferences.getBoolean(IdeDesignTokens.Preference.TERMINAL_CURSOR_BLINK, true))
    }
    DisposableEffect(terminalPreferences) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                IdeDesignTokens.Preference.TERMINAL_FONT_SIZE -> {
                    terminalFontSizeSp = sharedPreferences?.getInt(
                        IdeDesignTokens.Preference.TERMINAL_FONT_SIZE,
                        IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE
                    ) ?: IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE
                }
                IdeDesignTokens.Preference.TERMINAL_AUTO_KEYBOARD -> {
                    autoShowKeyboard = sharedPreferences?.getBoolean(
                        IdeDesignTokens.Preference.TERMINAL_AUTO_KEYBOARD, true
                    ) ?: true
                }
                IdeDesignTokens.Preference.TERMINAL_CURSOR_BLINK -> {
                    cursorBlinkEnabled = sharedPreferences?.getBoolean(
                        IdeDesignTokens.Preference.TERMINAL_CURSOR_BLINK, true
                    ) ?: true
                }
            }
        }
        terminalPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { terminalPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // TerminalRenderer draws with Android Canvas/Paint, whose text size is in
    // physical pixels. Settings store SP, so convert once before configuring
    // the native View; otherwise text becomes noticeably too small on high-DPI
    // devices and row/column metrics are calculated from the wrong size.
    val autoKeyboardOnOpen = autoShowKeyboard
    val terminalFontSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        terminalFontSizeSp.toFloat(),
        appContext.resources.displayMetrics
    ).toInt().coerceAtLeast(1)

    if (!runtimeStatus.ready || shell == null) {
        val message = if (runtimeStatus.ready) {
            "Runtime smoke test passed, but no executable shell entry point was found."
        } else {
            runtimeStatus.message
        }
        Column(
            modifier = modifier.fillMaxSize().background(IdeDesignTokens.Color.Background).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
        ) {
            Text("Terminal runtime unavailable", color = IdeDesignTokens.Color.TextPrimary)
            Text(message, color = IdeDesignTokens.Color.TextSecondary)
            Text(
                "Open Settings → Runtime & Bootstrap to select the correct bootstrap ZIP and verify its SHA-256 checksum.",
                color = Color(0xFF9AA8BA)
            )
            Button(onClick = onOpenSettings) { Text("Open Runtime Settings") }
            Button(onClick = { runtimeRefreshToken++ }) { Text("Retry runtime check") }
        }
        return
    }

    val home = remember(layout) { layout.home.apply { mkdirs() } }
    val environment = remember(layout, shell) {
        layout.environment(
            inherited = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor"),
            extraPathEntries = listOf(File(layout.prefix, "bin"))
        ).map { (key, value) -> "$key=$value" }.toTypedArray()
    }
    val linker = remember { AndroidRuntimeLauncher.systemLinker }

    // This registry lives for the app process, not for the bottom-panel
    // composition. Hiding/reopening the panel only detaches the view; sessions
    // keep running with their scrollback, cwd, environment and foreground job.
    val tabs = TerminalSessionRegistry.tabs
    val selectedTabId = TerminalSessionRegistry.selectedTabId

    DisposableEffect(onCloseTerminal) {
        TerminalSessionRegistry.onLastSessionExited = onCloseTerminal
        onDispose {
            if (TerminalSessionRegistry.onLastSessionExited === onCloseTerminal) {
                TerminalSessionRegistry.onLastSessionExited = null
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(runtimeStatus.ready, shell.absolutePath, home.absolutePath, environment.contentHashCode()) {
        // Create the first session once. If the user exits the final shell,
        // don't immediately respawn it just because the registry is empty.
        if (runtimeStatus.ready && !TerminalSessionRegistry.initialSessionCreated) {
            TerminalSessionRegistry.initialSessionCreated = true
            if (tabs.isEmpty()) {
                TerminalSessionRegistry.createTab(
                    appContext, linker, shell, shellArguments, home, environment, terminalFontSizePx
                )
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().background(Color(0xFF0D1117)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEach { tab ->
                Row(
                    modifier = Modifier
                        .background(
                            if (tab.id == selectedTabId) Color(0xFF173D5C) else Color(0xFF151B23),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { TerminalSessionRegistry.select(tab.id) }
                        .padding(start = 10.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text(
                        tab.title,
                        color = if (tab.id == selectedTabId) Color(0xFFE8EDF5) else Color(0xFF9AA8BA),
                        fontWeight = if (tab.id == selectedTabId) FontWeight.SemiBold else FontWeight.Normal
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "×",
                        color = Color(0xFF9AA8BA),
                        modifier = Modifier.clickable { TerminalSessionRegistry.closeTab(tab.id) }
                    )
                }
            }
            Text(
                "+",
                color = Color(0xFF9CBEFF),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xFF151B23), RoundedCornerShape(8.dp))
                    .clickable {
                        TerminalSessionRegistry.createTab(
                            appContext, linker, shell, shellArguments, home, environment, terminalFontSizePx
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        val activeTab = tabs.firstOrNull { it.id == selectedTabId }
        if (activeTab == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("No terminal sessions", color = Color(0xFFE8EDF5))
                Button(onClick = {
                    TerminalSessionRegistry.createTab(
                        appContext, linker, shell, shellArguments, home, environment, terminalFontSizePx
                    )
                }) { Text("New terminal") }
            }
        } else {
            val terminalViewRef = remember(activeTab.id) { mutableStateOf<TerminalView?>(null) }
            DisposableEffect(activeTab.id, activeTab.client) {
                onDispose {
                    // Detach only. Do NOT finish the process when the panel is
                    // hidden or the user switches to Editor/another panel.
                    activeTab.client.attach(null)
                    terminalViewRef.value = null
                }
            }
            TerminalExtraKeys(
                client = activeTab.client,
                terminalView = terminalViewRef.value
            )
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0D1117)),
                factory = { viewContext ->
                    TerminalView(viewContext, null).apply {
                        setTextSize(terminalFontSizePx)
                        setTypeface(Typeface.MONOSPACE)
                        val terminalViewClient = IdeTermuxViewClient(viewContext)
                        setTerminalViewClient(terminalViewClient)
                        terminalViewClient.attachView(this)
                        activeTab.client.attach(this)
                        activeTab.client.setCursorBlinkEnabled(cursorBlinkEnabled)
                        terminalViewRef.value = this
                        attachSession(activeTab.session)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        post {
                            requestFocus()
                            if (autoKeyboardOnOpen) {
                                postDelayed({
                                    (viewContext.getSystemService(Context.INPUT_METHOD_SERVICE) as?
                                        android.view.inputmethod.InputMethodManager)
                                        ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                }, 180L)
                            }
                        }
                    }
                },
                update = { view ->
                    activeTab.client.attach(view)
                    activeTab.client.setCursorBlinkEnabled(cursorBlinkEnabled)
                    terminalViewRef.value = view
                    if (view.mTermSession !== activeTab.session) view.attachSession(activeTab.session)
                    view.setTextSize(terminalFontSizePx)
                }
            )
        }
    }
}



@Composable
private fun TerminalExtraKeys(
    client: IdeTermuxSessionClient,
    terminalView: TerminalView?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        TerminalExtraKey("CTRL", client.readControlKey()) { client.toggleControl() }
        TerminalExtraKey("ALT", client.readAltKey()) { client.toggleAlt() }
        TerminalExtraKey("SHIFT", client.readShiftKey()) { client.toggleShift() }
        TerminalExtraKey("FN", client.readFnKey()) { client.toggleFn() }
        TerminalExtraKey("ESC") { terminalView?.mTermSession?.writeCodePoint(false, 27) }
        TerminalExtraKey("TAB") { terminalView?.mTermSession?.writeCodePoint(false, 9) }
        TerminalExtraKey("↑") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, client.keyModifiers()) }
        TerminalExtraKey("↓") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, client.keyModifiers()) }
        TerminalExtraKey("←") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, client.keyModifiers()) }
        TerminalExtraKey("→") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, client.keyModifiers()) }
        TerminalExtraKey("HOME") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, client.keyModifiers()) }
        TerminalExtraKey("END") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_MOVE_END, client.keyModifiers()) }
        TerminalExtraKey("PGUP") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_PAGE_UP, client.keyModifiers()) }
        TerminalExtraKey("PGDN") { terminalView?.handleKeyCode(KeyEvent.KEYCODE_PAGE_DOWN, client.keyModifiers()) }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (selected) Color(0xFF071426) else Color(0xFFCFD9E6),
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(
                if (selected) Color(0xFF83B7FF) else Color(0xFF202B38),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

/**
 * Caches only a successfully verified prefix for the lifetime of this process.
 * The bottom sheet's composition is recreated when it is dismissed, so keeping
 * this cache outside the composable avoids rescanning the bootstrap and starting
 * a smoke-test shell on every open.
 */
private object RuntimeStartupCheckCache {
    @Volatile private var prefixPath: String? = null
    @Volatile private var successfulStatus: RuntimeStatus? = null

    fun get(prefix: String): RuntimeStatus? =
        if (prefixPath == prefix) successfulStatus else null

    @Synchronized
    fun store(prefix: String, status: RuntimeStatus) {
        prefixPath = prefix
        successfulStatus = status
    }

    @Synchronized
    fun invalidate(prefix: String) {
        if (prefixPath == prefix) {
            prefixPath = null
            successfulStatus = null
        }
    }
}

private data class TerminalTab(
    val id: Int,
    val title: String,
    val session: TerminalSession,
    val client: IdeTermuxSessionClient,
)

/**
 * Process-scoped terminal sessions. A panel composable is disposable; a shell
 * session is not. Sessions are intentionally finished only when the user closes
 * their tab or Android kills the app process.
 */
private object TerminalSessionRegistry {
    val tabs = mutableStateListOf<TerminalTab>()
    var selectedTabId by mutableIntStateOf(0)
        private set
    var initialSessionCreated = false
    var onLastSessionExited: (() -> Unit)? = null
    private var nextId = 1

    fun select(id: Int) {
        if (tabs.any { it.id == id }) selectedTabId = id
    }

    /**
     * A finished shell is removed from the registry. If it was selected, move
     * to the immediately previous tab (4 -> 3 -> 2 -> 1). Exiting the final
     * shell closes the terminal panel instead of creating a replacement shell.
     */
    fun onSessionFinished(session: TerminalSession) {
        val index = tabs.indexOfFirst { it.session === session }
        if (index < 0) return
        val wasSelected = tabs[index].id == selectedTabId
        val removed = tabs.removeAt(index)
        removed.client.attach(null)

        if (tabs.isEmpty()) {
            selectedTabId = 0
            // Allow a fresh initial shell the next time the user opens Terminal.
            initialSessionCreated = false
            onLastSessionExited?.invoke()
            return
        }

        if (wasSelected) {
            selectedTabId = tabs.getOrNull(index - 1)?.id ?: tabs.first().id
        }
    }

    fun createTab(
        context: Context,
        linker: File,
        shell: File,
        shellArguments: Array<String>,
        home: File,
        environment: Array<String>,
        terminalFontSize: Int,
    ) {
        val id = nextId++
        val client = IdeTermuxSessionClient(context.applicationContext)
        // TerminalSession passes argv[] directly to execvp. argv[0] is the
        // linker, followed by the embedded shell ELF and its optional flags.
        val args = arrayOf(linker.absolutePath, shell.absolutePath, *shellArguments)
        val session = TerminalSession(
            linker.absolutePath,
            home.absolutePath,
            args,
            environment.copyOf(),
            2_000,
            client
        )
        tabs.add(TerminalTab(id, "Terminal $id", session, client))
        selectedTabId = id
    }

    fun closeTab(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val removed = tabs.removeAt(index)
        removed.client.attach(null)
        removed.session.finishIfRunning()
        if (tabs.isEmpty()) {
            selectedTabId = 0
            initialSessionCreated = false
            onLastSessionExited?.invoke()
        } else if (selectedTabId == id) {
            // Closing a selected tab follows shell-exit behavior: select the
            // immediately previous tab, falling back to the first remaining tab.
            selectedTabId = tabs.getOrNull(index - 1)?.id ?: tabs.first().id
        }
    }
}

private const val DEFAULT_CURSOR_BLINK_RATE_MS = 530

private class IdeTermuxSessionClient(
    private val context: Context
) : TerminalSessionClient {
    @Volatile private var terminalView: TerminalView? = null
    // Snapshot-backed modifier state keeps the extra-key toolbar in sync with
    // sticky modifier toggles while remaining readable by TerminalView callbacks.
    private var controlPressed by mutableStateOf(false)
    private var altPressed by mutableStateOf(false)
    private var shiftPressed by mutableStateOf(false)
    private var fnPressed by mutableStateOf(false)

    fun toggleControl() { controlPressed = !controlPressed }
    fun toggleAlt() { altPressed = !altPressed }
    fun toggleShift() { shiftPressed = !shiftPressed }
    fun toggleFn() { fnPressed = !fnPressed }

    fun keyModifiers(): Int {
        var modifiers = 0
        if (controlPressed) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (altPressed) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (shiftPressed) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        return modifiers
    }

    @Volatile private var cursorBlinkEnabled = true

    fun setCursorBlinkEnabled(enabled: Boolean) {
        cursorBlinkEnabled = enabled
        terminalView?.let { view ->
            view.setTerminalCursorBlinkerRate(if (enabled) DEFAULT_CURSOR_BLINK_RATE_MS else 0)
            view.setTerminalCursorBlinkerState(enabled, true)
        }
    }

    fun attach(view: TerminalView?) {
        val previous = terminalView
        if (previous !== view) {
            // A detached bottom-sheet view must not keep a Handler callback
            // alive or continue invalidating an off-screen view.
            previous?.setTerminalCursorBlinkerState(false, false)
        }
        terminalView = view
        view?.setTerminalCursorBlinkerRate(if (cursorBlinkEnabled) DEFAULT_CURSOR_BLINK_RATE_MS else 0)
        view?.setTerminalCursorBlinkerState(cursorBlinkEnabled, true)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        terminalView?.post { terminalView?.invalidate() }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
        // Session callbacks can arrive off the UI thread. Update Compose state
        // and dismiss the sheet on the main thread.
        Handler(Looper.getMainLooper()).post {
            TerminalSessionRegistry.onSessionFinished(finishedSession)
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        if (text != null && session?.isRunning == true) session.write(text.toString())
    }

    override fun onBell(session: TerminalSession) {
        // Bell handling is intentionally non-audio, but still refresh the view
        // in case the terminal changed visual state with the bell sequence.
        terminalView?.post { terminalView?.invalidate() }
    }

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.post { terminalView?.invalidate() }
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.post {
            terminalView?.setTerminalCursorBlinkerState(state && cursorBlinkEnabled, true)
        }
    }
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String): Unit { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String): Unit { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String): Unit { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String): Unit { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String): Unit { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception): Unit {
        android.util.Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception): Unit { android.util.Log.e(tag, e.message, e) }
}

private class IdeTermuxViewClient(
    private val context: Context
) : TerminalViewClient {
    @Volatile private var terminalView: TerminalView? = null

    fun attachView(view: TerminalView?) {
        terminalView = view
    }

    override fun onScale(scale: Float): Float = scale

    // Match Termux: tapping the terminal focuses the emulator and opens the
    // Android IME again after it has been dismissed with Back.
    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView ?: return
        view.post {
            view.requestFocus()
            val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            inputMethodManager?.showSoftInput(
                view,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
            )
        }
    }
    // Android Back is handled by the IDE panel, not injected as Escape into
    // the shell. Escape here can leave readline/completion prompts in a bad state.
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = controlPressed
    override fun readAltKey(): Boolean = altPressed
    override fun readShiftKey(): Boolean = shiftPressed
    override fun readFnKey(): Boolean = fnPressed
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {
        terminalView?.let { view ->
            view.setTerminalCursorBlinkerRate(if (cursorBlinkEnabled) DEFAULT_CURSOR_BLINK_RATE_MS else 0)
            view.setTerminalCursorBlinkerState(cursorBlinkEnabled, true)
        }
    }
    override fun logError(tag: String, message: String): Unit { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String): Unit { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String): Unit { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String): Unit { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String): Unit { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception): Unit {
        android.util.Log.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception): Unit { android.util.Log.e(tag, e.message, e) }
}
