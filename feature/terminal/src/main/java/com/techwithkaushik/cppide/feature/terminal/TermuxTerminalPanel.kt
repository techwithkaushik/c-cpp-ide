package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.techwithkaushik.cppide.integration.termuxruntime.BootstrapSmokeTest
import com.techwithkaushik.cppide.integration.termuxruntime.EmbeddedTermuxRuntime
import com.techwithkaushik.cppide.integration.termuxruntime.RuntimeLayout
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
fun TermuxTerminalPanel(modifier: Modifier = Modifier, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val layout = remember(appContext) { RuntimeLayout.fromAppFilesDirectory(appContext.filesDir) }
    var runtimeRefreshToken by remember(appContext) { mutableIntStateOf(0) }
    val runtimeStatus = produceState(
        initialValue = RuntimeStatus(false, "Checking the embedded runtime…"),
        key1 = appContext,
        key2 = runtimeRefreshToken
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                val runtime = EmbeddedTermuxRuntime(appContext.filesDir)
                if (!runtime.hasBootstrapLayout()) {
                    RuntimeStatus(
                        ready = false,
                        message = "No runtime is installed. Open Settings → Runtime & Bootstrap and select a bootstrap ZIP."
                    )
                } else {
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
        appContext.getSharedPreferences("ide_settings", Context.MODE_PRIVATE)
    }
    val terminalFontSize = terminalPreferences.getInt("terminal_font_size", 13)
    val autoShowKeyboard = terminalPreferences.getBoolean("terminal_auto_keyboard", true)

    if (!runtimeStatus.ready || shell == null) {
        val message = if (runtimeStatus.ready) {
            "Runtime smoke test passed, but no executable shell entry point was found."
        } else {
            runtimeStatus.message
        }
        Column(
            modifier = modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Terminal runtime unavailable", color = Color(0xFFE8EDF5))
            Text(message, color = Color(0xFF9AA8BA))
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
    val terminalClient = remember(appContext) { IdeTermuxSessionClient(appContext) }
    val environment = remember(layout, shell) {
        layout.environment(
            inherited = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor"),
            extraPathEntries = listOf(File(layout.prefix, "bin"))
        ).map { (key, value) -> "$key=$value" }.toTypedArray()
    }
    val session = remember(shell, home, environment.contentHashCode()) {
        TerminalSession(
            shell.absolutePath,
            home.absolutePath,
            shellArguments,
            environment,
            2_000,
            terminalClient
        )
    }

    DisposableEffect(session, terminalClient) {
        onDispose {
            terminalClient.attach(null)
            session.finishIfRunning()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize().background(Color(0xFF0D1117)),
        factory = { viewContext ->
            TerminalView(viewContext, null).apply {
                setTextSize(terminalFontSize)
                setTypeface(Typeface.MONOSPACE)
                setTerminalViewClient(IdeTermuxViewClient(viewContext))
                terminalClient.attach(this)
                attachSession(session)
                isFocusable = true
                isFocusableInTouchMode = true
                post {
                    requestFocus()
                    if (autoShowKeyboard) {
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
            if (view.mTermSession !== session) view.attachSession(session)
        }
    )
}


private class IdeTermuxSessionClient(
    private val context: Context
) : TerminalSessionClient {
    @Volatile private var terminalView: TerminalView? = null

    fun attach(view: TerminalView?) {
        terminalView = view
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        terminalView?.post { terminalView?.invalidate() }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.post { terminalView?.onScreenUpdated() }
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

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
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
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit
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
