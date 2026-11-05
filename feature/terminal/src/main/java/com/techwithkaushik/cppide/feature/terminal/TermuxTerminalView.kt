package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import kotlin.math.floor

/** Maps laid-out cell dimensions to the PTY, suppressing duplicate resize calls. */
private class PtyResizeListener(
    private val session: TerminalSession,
    density: Float,
    fontSizeSp: Int,
) : View.OnLayoutChangeListener {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSizeSp * density
    }
    private var lastColumns = 0
    private var lastRows = 0

    fun setFontSize(fontSizeSp: Int, density: Float) {
        val size = fontSizeSp * density
        if (paint.textSize != size) { paint.textSize = size; lastColumns = 0; lastRows = 0 }
    }

    override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
        val width = right - left - view.paddingLeft - view.paddingRight
        val height = bottom - top - view.paddingTop - view.paddingBottom
        if (width <= 0 || height <= 0) return
        val cellWidth = paint.measureText("W").coerceAtLeast(1f)
        val metrics = paint.fontMetrics
        val cellHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val columns = floor(width / cellWidth).toInt().coerceAtLeast(1)
        val rows = floor(height / cellHeight).toInt().coerceAtLeast(1)
        if (columns == lastColumns && rows == lastRows) return
        lastColumns = columns
        lastRows = rows
        session.updateSize(columns, rows)
    }
}

@Composable
internal fun TermuxTerminalView(session: TerminalSession, modifier: Modifier = Modifier, fontSizeSp: Int = 14, onSessionFinished: (TerminalSession) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context, null).apply {
                setTerminalViewClient(createTerminalViewClient(context))
                setTextSize(fontSizeSp)
                setTypeface(Typeface.MONOSPACE)
                val listener = PtyResizeListener(session, resources.displayMetrics.density, fontSizeSp)
                setTag(android.R.id.content, listener)
                addOnLayoutChangeListener(listener)
                attachSession(session)
                post { listener.onLayoutChange(this, left, top, right, bottom, left, top, right, bottom) }
            }
        },
        update = { view ->
            (view.getTag(android.R.id.content) as? PtyResizeListener)?.setFontSize(fontSizeSp, view.resources.displayMetrics.density)
            if (view.mTermSession !== session) view.attachSession(session)
            view.setTextSize(fontSizeSp)
        },
        onRelease = { view ->
            (view.getTag(android.R.id.content) as? PtyResizeListener)?.let(view::removeOnLayoutChangeListener)
            view.setTag(android.R.id.content, null)
            if (view.mTermSession === session) view.attachSession(null)
        }
    )
}

internal fun createTermuxSessionClient(context: Context, onSessionFinished: (TerminalSession) -> Unit = {}): TerminalSessionClient = object : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = onSessionFinished.invoke(finishedSession)
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        if (text != null) { val bytes = text.toString().toByteArray(Charsets.UTF_8); session.write(bytes, 0, bytes.size) }
    }
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

private fun createTerminalViewClient(context: Context): TerminalViewClient = object : TerminalViewClient {
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
