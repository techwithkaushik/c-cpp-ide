package com.techwithkaushik.cppide.feature.editor

import android.graphics.Color as AndroidColor
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import java.util.concurrent.atomic.AtomicBoolean
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

private fun showEditorContextMenu(anchor: View) {
    val editor = anchor as? CodeEditor ?: return
    PopupMenu(anchor.context, anchor).apply {
        menu.add(0, 1, 0, "Undo")
        menu.add(0, 2, 1, "Redo")
        menu.add(0, 3, 2, "Cut")
        menu.add(0, 4, 3, "Copy")
        menu.add(0, 5, 4, "Paste")
        menu.add(0, 6, 5, "Select All")
        setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> editor.undo()
                2 -> editor.redo()
                3 -> editor.cutText()
                4 -> editor.copyText()
                5 -> editor.pasteText()
                6 -> editor.text.selectAll()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        show()
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
    val suppressProgrammaticChanges = androidx.compose.runtime.remember { AtomicBoolean(false) }
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
                // Touch long-press and mouse right-click both expose familiar editor actions.
                setOnLongClickListener { view ->
                    showEditorContextMenu(view)
                    true
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setOnContextClickListener { view ->
                        showEditorContextMenu(view)
                        true
                    }
                }
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    if (!suppressProgrammaticChanges.get()) currentOnCodeChange.value(text.toString())
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
            if (editor.text.toString() != code) {
                suppressProgrammaticChanges.set(true)
                try {
                    editor.setText(code)
                } finally {
                    suppressProgrammaticChanges.set(false)
                }
            }
        },
        onRelease = { editor ->
            controller?.detach(editor)
            editor.release()
        }
    )
}
