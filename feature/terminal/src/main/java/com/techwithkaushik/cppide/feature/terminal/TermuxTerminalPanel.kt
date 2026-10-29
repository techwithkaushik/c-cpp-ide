package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.techwithkaushik.cppide.integration.termuxruntime.RuntimeLayout
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * Hosts the upstream Termux terminal emulator in the IDE's bottom panel.
 *
 * It deliberately refuses to fall back to Android's /system/bin/sh: only a
 * successfully installed app-private Termux prefix is allowed to start.
 */
@Composable
fun TermuxTerminalPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val layout = remember(context) { RuntimeLayout.fromAppFilesDirectory(context.filesDir) }
    val shell = remember(layout) {
        listOf(File(layout.prefix, "bin/sh"), File(layout.prefix, "bin/bash"))
            .firstOrNull { it.isFile && it.canExecute() }
    }

    if (shell == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0D1117))
                .padding(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Termux terminal module connected", color = Color(0xFF8CC8FF), fontSize = 14.sp)
                Text(
                    "Runtime not installed",
                    color = Color(0xFFE8EDF5),
                    fontSize = 13.sp
                )
                Text(
                    "The app-private Termux bootstrap must be provisioned for this app's package path before a shell can start. Android's host shell is intentionally not used as a fallback.",
                    color = Color(0xFF9AA7B5),
                    fontSize = 11.sp
                )
            }
        }
        return
    }

    val home = layout.home.apply { mkdirs() }
    val terminalClient = remember(context) { IdeTermuxSessionClient(context) }
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
    override fun logError(tag: String, message: String) = android.util.Log.e(tag, message)
    override fun logWarn(tag: String, message: String) = android.util.Log.w(tag, message)
    override fun logInfo(tag: String, message: String) = android.util.Log.i(tag, message)
    override fun logDebug(tag: String, message: String) = android.util.Log.d(tag, message)
    override fun logVerbose(tag: String, message: String) = android.util.Log.v(tag, message)
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        android.util.Log.e(tag, message, e)
    override fun logStackTrace(tag: String, e: Exception) = android.util.Log.e(tag, e.message, e)
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
    override fun logError(tag: String, message: String) = android.util.Log.e(tag, message)
    override fun logWarn(tag: String, message: String) = android.util.Log.w(tag, message)
    override fun logInfo(tag: String, message: String) = android.util.Log.i(tag, message)
    override fun logDebug(tag: String, message: String) = android.util.Log.d(tag, message)
    override fun logVerbose(tag: String, message: String) = android.util.Log.v(tag, message)
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        android.util.Log.e(tag, message, e)
    override fun logStackTrace(tag: String, e: Exception) = android.util.Log.e(tag, e.message, e)
}
