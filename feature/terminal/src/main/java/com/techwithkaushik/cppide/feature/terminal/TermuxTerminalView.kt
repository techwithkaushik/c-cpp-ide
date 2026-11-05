package com.techwithkaushik.cppide.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.abs

/** Measures terminal cells after layout and synchronizes the PTY only when its grid changes. */
private class PtyResizeListener(
    private val session: TerminalSession,
    density: Float,
    fontSizeSp: Int,
) : View.OnLayoutChangeListener {
    private val density = density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = fontSizeSp * density }
    private var lastColumns = 0
    private var lastRows = 0

    fun setFontSize(fontSizeSp: Int) {
        val size = fontSizeSp * density
        if (abs(paint.textSize - size) > 0.01f) { paint.textSize = size; lastColumns = 0; lastRows = 0 }
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

/** TerminalView supplies a cumulative pinch scale; convert it to incremental font changes. */
private class TerminalZoomController(
    private val view: TerminalView,
    private val resizeListener: PtyResizeListener,
    initialFontSizeSp: Int,
) {
    private var configuredFontSizeSp = initialFontSizeSp
    private var fontSizeSp = initialFontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
    private var lastScale = 1f

    fun updateConfiguredFontSize(newFontSizeSp: Int) {
        if (newFontSizeSp == configuredFontSizeSp) return
        configuredFontSizeSp = newFontSizeSp
        fontSizeSp = newFontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        lastScale = 1f
        view.setTextSize(fontSizeSp)
        resizeListener.setFontSize(fontSizeSp)
        view.requestLayout()
    }

    fun onScale(cumulativeScale: Float): Float {
        val targetScale = cumulativeScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = targetScale / lastScale
        if (abs(ratio - 1f) >= SCALE_EPSILON) {
            val nextSize = (fontSizeSp * ratio).roundToInt().coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (nextSize != fontSizeSp) {
                fontSizeSp = nextSize
                view.setTextSize(fontSizeSp)
                resizeListener.setFontSize(fontSizeSp)
                view.requestLayout()
            }
        }
        lastScale = targetScale
        return targetScale
    }

    companion object {
        private const val MIN_FONT_SP = 8
        private const val MAX_FONT_SP = 32
        private const val MIN_SCALE = 0.65f
        private const val MAX_SCALE = 2.0f
        private const val SCALE_EPSILON = 0.015f
    }
}

@Composable
internal fun TermuxTerminalView(session: TerminalSession, modifier: Modifier = Modifier, fontSizeSp: Int = 14, onSessionFinished: (TerminalSession) -> Unit = {}) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TerminalView(context, null).apply {
                setTextSize(fontSizeSp)
                setTypeface(Typeface.MONOSPACE)
                val resizeListener = PtyResizeListener(session, resources.displayMetrics.density, fontSizeSp)
                val zoomController = TerminalZoomController(this, resizeListener, fontSizeSp)
                setTag(android.R.id.content, resizeListener)
                setTag(android.R.id.custom, zoomController)
                setTerminalViewClient(createTerminalViewClient(context, this, zoomController))
                addOnLayoutChangeListener(resizeListener)
                attachSession(session)
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                post { resizeListener.onLayoutChange(this, left, top, right, bottom, left, top, right, bottom) }
            }
        },
        update = { view ->
            (view.getTag(android.R.id.custom) as? TerminalZoomController)?.updateConfiguredFontSize(fontSizeSp)
            if (view.mTermSession !== session) view.attachSession(session)
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
        },
        onRelease = { view ->
            (view.getTag(android.R.id.content) as? PtyResizeListener)?.let(view::removeOnLayoutChangeListener)
            view.setTag(android.R.id.content, null)
            view.setTag(android.R.id.custom, null)
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

private fun createTerminalViewClient(context: Context, terminalView: TerminalView, zoomController: TerminalZoomController): TerminalViewClient = object : TerminalViewClient {
    override fun onScale(scale: Float): Float = zoomController.onScale(scale)
    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = terminalView.hasFocus()
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
