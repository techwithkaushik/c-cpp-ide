package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
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
fun TermuxTerminalPanel(modifier: Modifier = Modifier) {
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
                        message = "Runtime not installed. Install a bootstrap built for this app before starting a shell."
                    )
                } else {
                    val smokeTest = BootstrapSmokeTest(runtime).run()
                    if (smokeTest.success) {
                        RuntimeStatus(true, "")
                    } else {
                        RuntimeStatus(
                            ready = false,
                            message = smokeTest.failureReason
                                ?: "The embedded shell failed its execution test."
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
        else listOf(File(layout.prefix, "bin/sh"), File(layout.prefix, "bin/bash"))
            .firstOrNull { it.isFile && it.canExecute() }
    }

    if (!runtimeStatus.ready || shell == null) {
        val message = if (runtimeStatus.ready) {
            "Runtime smoke test passed, but no executable shell entry point was found."
        } else {
            runtimeStatus.message
        }
        RuntimeSetupPanel(
            message = message,
            onRuntimeReady = { runtimeRefreshToken++ },
            modifier = modifier
        )
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
            arrayOf("--login"),
            environment,
            2_000,
            terminalClient
        )
    }

    DisposableEffect(session) {
        onDispose { session.finishIfRunning() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize().background(Color(0xFF0D1117)),
        factory = { viewContext ->
            TerminalView(viewContext, null).apply {
                setTextSize(13)
                setTypeface(Typeface.MONOSPACE)
                setTerminalViewClient(IdeTermuxViewClient(viewContext))
                attachSession(session)
                requestFocus()
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
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

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
