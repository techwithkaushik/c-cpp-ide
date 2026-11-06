package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

val VsCodeBg = Color(0xFF1E1E1E)
val VsCodeSidebarBg = Color(0xFF252526)
val VsCodeTerminalBg = Color(0xFF181818)

/**
 * Stable VS Code-style workspace frame. The caller owns editor and runtime state;
 * the shell only controls layout and keeps the dock composed while collapsed.
 */
@Composable
fun IdeMasterShellUi(
    modifier: Modifier = Modifier,
    bottomPanelVisible: Boolean,
    onCloseBottomPanel: () -> Unit,
    workspaceContent: @Composable () -> Unit,
    terminalDock: @Composable () -> Unit,
) {
    var dockFraction by remember { mutableStateOf(0.42f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(VsCodeBg)) {
    val availableHeight = maxHeight
    val dockHeight by animateDpAsState(
        targetValue = if (bottomPanelVisible) availableHeight * dockFraction else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "ide-terminal-dock-height",
    )

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(VsCodeBg)) {
            workspaceContent()
        }
        Column(
            Modifier.fillMaxWidth().height(dockHeight)
                .background(VsCodeTerminalBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
        ) {
            if (bottomPanelVisible) {
                Box(
                    Modifier.fillMaxWidth().height(12.dp)
                        .pointerInput(availableHeight) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val maxPx = with(density) { availableHeight.toPx() }.coerceAtLeast(1f)
                                dockFraction = (dockFraction - dragAmount / maxPx).coerceIn(0.22f, 0.72f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(42.dp).height(4.dp).background(Color(0xFF666666), RoundedCornerShape(2.dp)))
                }
                Row(
                    Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("TERMINAL", color = Color(0xFFCCCCCC), fontSize = 10.sp)
                    Text("×", Modifier.clickable(onClick = onCloseBottomPanel).padding(horizontal = 8.dp), color = Color(0xFFCCCCCC), fontSize = 16.sp)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    terminalDock()
                }
            }
        }
    }
    }
}
