package com.techwithkaushik.cppide.feature.editor

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Imperative command bridge for the Sora editor hosted by Compose.
 * The controller only references the currently attached view; it never owns document state.
 */
class SoraEditorController {
    private var editor: CodeEditor? = null

    fun undo() {
        editor?.undo()
    }

    fun redo() {
        editor?.redo()
    }

    fun selectAll() {
        editor?.text?.selectAll()
    }

    fun copy() {
        editor?.copyText()
    }

    fun paste() {
        editor?.pasteText()
    }

    fun cut() {
        editor?.cutText()
    }

    fun selectLine(line: Int) {
        val target = (line - 1).coerceAtLeast(0)
        editor?.let { view ->
            if (target < view.lineCount) {
                view.setSelection(target, 0)
                view.ensurePositionVisible(target, 0)
            }
        }
    }

    internal fun attach(editor: CodeEditor) {
        this.editor = editor
    }

    internal fun detach(editor: CodeEditor) {
        if (this.editor === editor) this.editor = null
    }
}

@Composable
fun SoraCodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: SoraEditorController? = null,
    fontSize: Float = 13f,
    wordWrap: Boolean = false
) {
    val currentOnCodeChange = rememberUpdatedState(onCodeChange)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                setText(code)
                setTextSize(fontSize)
                isWordwrap = wordWrap
                setLineNumberEnabled(true)
                setHighlightCurrentLine(true)
                setAutoCompletionEnabled(true)
                setTabWidth(4)
                setScrollBarEnabled(true)
                setHorizontalScrollBarEnabled(true)
                colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, AndroidColor.rgb(16, 19, 25))
                colorScheme.setColor(EditorColorScheme.TEXT_NORMAL, AndroidColor.rgb(217, 228, 244))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, AndroidColor.rgb(20, 25, 34))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER, AndroidColor.rgb(154, 168, 186))
                colorScheme.setColor(EditorColorScheme.CURRENT_LINE, AndroidColor.rgb(27, 35, 47))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, AndroidColor.rgb(20, 25, 34))
                colorScheme.setColor(EditorColorScheme.SELECTION_INSERT, AndroidColor.rgb(74, 132, 196))
                colorScheme.setColor(EditorColorScheme.SELECTION_HANDLE, AndroidColor.rgb(74, 132, 196))
                controller?.attach(this)
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    currentOnCodeChange.value(text.toString())
                }
            }
        },
        update = { editor ->
            editor.setTextSize(fontSize)
            editor.isWordwrap = wordWrap
            editor.setLineNumberEnabled(true)
            editor.setHighlightCurrentLine(true)
            editor.setAutoCompletionEnabled(true)
            editor.setTabWidth(4)
            editor.setScrollBarEnabled(true)
            editor.setHorizontalScrollBarEnabled(true)
            controller?.attach(editor)
            if (editor.text.toString() != code) editor.setText(code)
        },
        onRelease = { editor ->
            controller?.detach(editor)
            editor.release()
        }
    )
}
