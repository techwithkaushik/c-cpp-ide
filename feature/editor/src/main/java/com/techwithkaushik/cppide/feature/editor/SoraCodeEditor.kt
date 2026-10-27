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
 * Small imperative bridge for toolbar commands while the editor is hosted by Compose.
 * The controller only references the currently attached view; it never owns editor state.
 */
class SoraEditorController {
    private var editor: CodeEditor? = null

    fun undo() {
        editor?.undo()
    }

    fun redo() {
        editor?.redo()
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
                colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, AndroidColor.rgb(16, 19, 25))
                colorScheme.setColor(EditorColorScheme.TEXT_NORMAL, AndroidColor.rgb(217, 228, 244))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, AndroidColor.rgb(20, 25, 34))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER, AndroidColor.rgb(154, 168, 186))
                colorScheme.setColor(EditorColorScheme.CURRENT_LINE, AndroidColor.rgb(27, 35, 47))
                controller?.attach(this)
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    currentOnCodeChange.value(text.toString())
                }
            }
        },
        update = { editor ->
            editor.setTextSize(fontSize)
            editor.isWordwrap = wordWrap
            controller?.attach(editor)
            if (editor.text.toString() != code) editor.setText(code)
        },
        onRelease = { editor ->
            controller?.detach(editor)
            editor.release()
        }
    )
}
