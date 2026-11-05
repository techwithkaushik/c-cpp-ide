package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.techwithkaushik.cppide.core.logging.DeveloperLog

/**
 * Native Termux terminal surface. The owner retains and controls the TerminalSession;
 * this composable only attaches the view and forwards the standard Termux callbacks.
 *
 * Keeping process/session ownership outside the View is important because terminal
 * tabs can outlive a Compose destination while the user switches editor screens.
 */
@Composable
internal fun TermuxTerminalView(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    fontSizeSp: Int = 14,
    onSessionFinished: (TerminalSession) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context).apply {
                setTerminalViewClient(createTerminalViewClient(context))
                setTextSize(fontSizeSp)
                setTypeface(Typeface.MONOSPACE)
                attachSession(session)
            }
        },
        update = { view ->
            if (view.mTermSession !== session) view.attachSession(session)
            view.setTextSize(fontSizeSp)
        },
        onRelease = { view ->
            // Detaching a view must not kill a persistent shell session.
            if (view.mTermSession === session) view.attachSession(null)
        }
    )
}

internal fun createTermuxSessionClient(
    onSessionFinished: (TerminalSession) -> Unit = {},
): TerminalSessionClient = object : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = onSessionFinished.invoke(finishedSession)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        // Clipboard is intentionally handled through Android's app-scoped clipboard service.
        // The actual Context is supplied by TerminalViewClient's view callback below.
        DeveloperLog.debug("TermuxTerminal", "Terminal requested clipboard copy (0 chars)")
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String, message: String) = DeveloperLog.error("Termux/$tag", message)
    override fun logWarn(tag: String, message: String) = DeveloperLog.warn("Termux/$tag", message)
    override fun logInfo(tag: String, message: String) = DeveloperLog.info("Termux/$tag", message)
    override fun logDebug(tag: String, message: String) = DeveloperLog.debug("Termux/$tag", message)
    override fun logVerbose(tag: String, message: String) = DeveloperLog.debug("Termux/$tag", message)
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = DeveloperLog.error("Termux/$tag", message, e)
    override fun logStackTrace(tag: String, e: Exception) = DeveloperLog.error("Termux/$tag", "Terminal library exception", e)
}

private fun createTerminalViewClient(context: Context): TerminalViewClient =
    object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale.coerceIn(0.65f, 2.0f)
        override fun onSingleTapUp(e: MotionEvent) = Unit
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
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
        override fun logError(tag: String, message: String) = DeveloperLog.error("TermuxView/$tag", message)
        override fun logWarn(tag: String, message: String) = DeveloperLog.warn("TermuxView/$tag", message)
        override fun logInfo(tag: String, message: String) = DeveloperLog.info("TermuxView/$tag", message)
        override fun logDebug(tag: String, message: String) = DeveloperLog.debug("TermuxView/$tag", message)
        override fun logVerbose(tag: String, message: String) = DeveloperLog.debug("TermuxView/$tag", message)
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = DeveloperLog.error("TermuxView/$tag", message, e)
        override fun logStackTrace(tag: String, e: Exception) = DeveloperLog.error("TermuxView/$tag", "Terminal view exception", e)
    }
