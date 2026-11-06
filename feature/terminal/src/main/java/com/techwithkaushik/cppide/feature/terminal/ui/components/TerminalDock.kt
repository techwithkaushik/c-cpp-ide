package com.techwithkaushik.cppide.feature.terminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.feature.terminal.ui.theme.*

/**
 * VS Code-style bottom panel.
 *
 * The panel header is always the top edge of this dock. When expanded, the
 * selected tool's content is placed immediately below it. The IDE status bar
 * is deliberately not rendered here: IdeMasterShellUi owns it as the next
 * sibling, keeping it pinned to the physical bottom of the workspace.
 */
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
    val panels = listOf(
        Triple("TERMINAL", ">_", "Terminal"),
        Triple("BUILD", "⌁", "Build"),
        Triple("PROBLEMS", "⚠", "Problems"),
        Triple("OUTPUT", "☰", "Output")
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VsCodeTerminal)
    ) {
        if (expanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(VsCodeTerminal)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onHeightDelta(drag.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .width(36.dp)
                        .height(3.dp)
                        .background(VsCodeBorder)
                )
            }
        }

        // Persistent panel tab strip. It remains visible even when the
        // content area is collapsed, matching the compact VS Code panel bar.
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(VsCodeTerminal),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                panels.forEach { (label, glyph, value) ->
                    val selected = selectedPanel.equals(value, ignoreCase = true) ||
                        selectedPanel.equals(label, ignoreCase = true)
                    Column(
                        Modifier
                            .height(36.dp)
                            .clickable { onSelectPanel(value) }
                            .padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                glyph,
                                color = if (selected) VsCodeAccent else VsCodeMutedText,
                                fontSize = 17.sp
                            )
                            Text(
                                label,
                                color = if (selected) VsCodeText else VsCodeMutedText,
                                fontSize = 11.sp,
                                letterSpacing = 0.35.sp
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (selected) VsCodeAccent else VsCodeTerminal)
                        )
                    }
                }
            }

            Text(
                if (expanded) "⌄" else "⌃",
                Modifier
                    .clickable(onClick = onClose)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                color = VsCodeMutedText,
                fontSize = 18.sp
            )
        }

        // Tool content is below the tab strip, never between the tab strip
        // and the status bar. The status bar is rendered by the parent after
        // this dock and therefore remains the final bottom row.
        if (expanded) {
            Box(
                Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .background(VsCodeTerminal)
            ) {
                content()
            }
        }
    }
}
