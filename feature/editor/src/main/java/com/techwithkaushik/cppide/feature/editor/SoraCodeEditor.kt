package com.techwithkaushik.cppide.feature.editor

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
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
        editor?.selectAll()
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
                6 -> editor.selectAll()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        show()
    }
}


/** VS Code-inspired editor palettes. Keep the editor canvas and gutter in sync. */
private fun configureEditorTheme(editor: CodeEditor, themeName: String) {
    val colors = when (themeName) {
        "Light+ (VS Code)" -> intArrayOf(
            AndroidColor.rgb(243, 243, 243), // background
            AndroidColor.rgb(30, 30, 30),    // text
            AndroidColor.rgb(237, 237, 237), // gutter
            AndroidColor.rgb(133, 133, 133), // line number
            AndroidColor.rgb(232, 232, 232), // current line
            AndroidColor.rgb(220, 235, 252)  // selection/caret
        )
        "Monokai" -> intArrayOf(
            AndroidColor.rgb(39, 40, 34),
            AndroidColor.rgb(248, 248, 242),
            AndroidColor.rgb(45, 46, 40),
            AndroidColor.rgb(144, 145, 133),
            AndroidColor.rgb(55, 56, 48),
            AndroidColor.rgb(73, 72, 62)
        )
        "Solarized Dark" -> intArrayOf(
            AndroidColor.rgb(0, 43, 54),
            AndroidColor.rgb(131, 148, 150),
            AndroidColor.rgb(7, 54, 66),
            AndroidColor.rgb(101, 123, 131),
            AndroidColor.rgb(7, 54, 66),
            AndroidColor.rgb(38, 70, 83)
        )
        else -> intArrayOf(
            AndroidColor.rgb(30, 30, 30),
            AndroidColor.rgb(212, 212, 212),
            AndroidColor.rgb(37, 37, 38),
            AndroidColor.rgb(133, 133, 133),
            AndroidColor.rgb(42, 45, 46),
            AndroidColor.rgb(38, 79, 120)
        )
    }
    editor.colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, colors[0])
    editor.colorScheme.setColor(EditorColorScheme.TEXT_NORMAL, colors[1])
    editor.colorScheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors[2])
    editor.colorScheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, colors[2])
    editor.colorScheme.setColor(EditorColorScheme.LINE_NUMBER, colors[3])
    editor.colorScheme.setColor(EditorColorScheme.CURRENT_LINE, colors[4])
    editor.colorScheme.setColor(EditorColorScheme.SELECTION_INSERT, colors[5])
    editor.colorScheme.setColor(EditorColorScheme.SELECTION_HANDLE, colors[5])
}

@Composable
fun SoraCodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    controller: SoraEditorController? = null,
    fontSize: Float = 14f,
    wordWrap: Boolean = false,
    themeName: String = "Dark+ (VS Code)"
) {
    val currentOnCodeChange = rememberUpdatedState(onCodeChange)
    val suppressProgrammaticChanges = androidx.compose.runtime.remember { AtomicBoolean(false) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                setText(code)
                setTextSize(fontSize)
                setTypefaceText(Typeface.MONOSPACE)
                isWordwrap = wordWrap
                setLineNumberEnabled(true)
                setHighlightCurrentLine(true)
                setTabWidth(4)
                setScrollBarEnabled(true)
                setHorizontalScrollBarEnabled(true)
                configureEditorTheme(this, themeName)
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
            editor.setTypefaceText(Typeface.MONOSPACE)
            configureEditorTheme(editor, themeName)
            editor.isWordwrap = wordWrap
            editor.setLineNumberEnabled(true)
            editor.setHighlightCurrentLine(true)
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
