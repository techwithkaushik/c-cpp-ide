package com.techwithkaushik.cppide.feature.editor

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

@Composable
fun SoraCodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnCodeChange = rememberUpdatedState(onCodeChange)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                setText(code)
                setTextSize(13f)
                isWordwrap = false
                colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, AndroidColor.rgb(16, 19, 25))
                colorScheme.setColor(EditorColorScheme.TEXT_NORMAL, AndroidColor.rgb(217, 228, 244))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, AndroidColor.rgb(20, 25, 34))
                colorScheme.setColor(EditorColorScheme.LINE_NUMBER, AndroidColor.rgb(154, 168, 186))
                colorScheme.setColor(EditorColorScheme.CURRENT_LINE, AndroidColor.rgb(27, 35, 47))
                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    currentOnCodeChange.value(text.toString())
                }
            }
        },
        update = { editor ->
            if (editor.text.toString() != code) editor.setText(code)
        }
    )
}
