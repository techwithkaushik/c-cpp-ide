package com.techwithkaushik.cppide.feature.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.feature.terminal.ui.theme.*

data class EditorBuffer(val id: String, val fileName: String, val dirty: Boolean = false)

@Composable
fun MultiTabEditor(
    buffers: List<EditorBuffer>,
    activeBufferId: String?,
    onSelectBuffer: (EditorBuffer) -> Unit,
    editorContent: @Composable (EditorBuffer?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(VsCodeBackground)) {
        Row(
            Modifier.height(35.dp).background(VsCodeSidebar).horizontalScroll(rememberScrollState())
        ) {
            buffers.forEach { buffer ->
                val active = buffer.id == activeBufferId
                Column(
                    Modifier.height(35.dp)
                        .widthIn(min = 104.dp)
                        .background(if (active) VsCodeBackground else VsCodeSidebar)
                        .clickable { onSelectBuffer(buffer) }
                ) {
                    Box(Modifier.height(1.dp).background(if (active) VsCodeAccent else VsCodeBorder))
                    Text(
                        buffer.fileName + if (buffer.dirty) " •" else "",
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (active) VsCodeText else VsCodeMutedText,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            editorContent(buffers.firstOrNull { it.id == activeBufferId })
        }
    }
}
