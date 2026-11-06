package com.techwithkaushik.cppide.feature.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.feature.terminal.ui.theme.*

@Composable
fun TerminalDock(
    selectedPanel: String,
    expanded: Boolean,
    onSelectPanel: (String) -> Unit,
    onClose: () -> Unit,
    onHeightDelta: (Float) -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().fillMaxSize().background(VsCodeTerminal)) {
        if (expanded) {
            Box(
                Modifier.fillMaxWidth().height(6.dp).background(VsCodeTerminal)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onHeightDelta(drag.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.width(36.dp).height(2.dp).background(VsCodeBorder)) }
        }
        Row(
            Modifier.fillMaxWidth().height(32.dp).background(VsCodeTerminal).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            listOf("TERMINAL", "BUILD", "PROBLEMS", "OUTPUT").forEach { label ->
                Column(Modifier.height(32.dp).clickable { onSelectPanel(label) }, verticalArrangement = Arrangement.Bottom) {
                    Text(label, color = if (selectedPanel.equals(label, true)) VsCodeText else VsCodeMutedText, fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedPanel.equals(label, true)) VsCodeAccent else VsCodeTerminal))
                }
            }
            Box(Modifier.weight(1f))
            if (expanded) Text("×", Modifier.clickable(onClick = onClose), color = VsCodeText, fontSize = 16.sp)
        }
        if (expanded) Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}
