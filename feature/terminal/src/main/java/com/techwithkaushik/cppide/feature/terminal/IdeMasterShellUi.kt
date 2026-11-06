package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val VsCodeBg = Color(0xFF1E1E1E)
val VsCodeSidebarBg = Color(0xFF252526)
val VsCodeTerminalBg = Color(0xFF181818)
private val VsCodeAccent = Color(0xFF007ACC)

/**
 * VS Code-style workspace with a directly draggable terminal partition.
 * The dock is resized in-place (without a BottomSheet or height animation) so
 * AndroidView receives each intermediate width/height during the drag.
 */
@Composable
fun IdeMasterShellUi(
    modifier: Modifier = Modifier,
    bottomPanelVisible: Boolean,
    onCloseBottomPanel: () -> Unit,
    workspaceContent: @Composable () -> Unit,
    terminalDock: @Composable () -> Unit,
) {
    var dockFraction by remember { mutableStateOf(0.38f) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().background(VsCodeBg)) {
        val availableHeight = maxHeight
        val minDockFraction = with(density) {
            (100.dp.toPx() / availableHeight.toPx().coerceAtLeast(1f)).coerceAtMost(0.98f)
        }
        val dockHeight = (availableHeight * dockFraction).coerceIn(100.dp, availableHeight)

        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(VsCodeBg)
            ) {
                workspaceContent()
            }

            if (bottomPanelVisible) {
                Column(
                    Modifier.fillMaxWidth()
                        .height(dockHeight)
                        .background(VsCodeTerminalBg)
                ) {
                    // A narrow drag rail is deliberately outside the AndroidView.
                    Box(
                        Modifier.fillMaxWidth()
                            .height(9.dp)
                            .pointerInput(availableHeight, minDockFraction) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val heightPx = with(density) { availableHeight.toPx() }
                                        .coerceAtLeast(1f)
                                    dockFraction = (dockFraction - dragAmount.y / heightPx)
                                        .coerceIn(minDockFraction, 0.98f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier.width(36.dp).height(3.dp)
                                .background(Color(0xFF555555), RoundedCornerShape(2.dp))
                        )
                    }

                    Row(
                        Modifier.fillMaxWidth().height(30.dp)
                            .background(VsCodeTerminalBg)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Column(
                            Modifier.height(30.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                "TERMINAL",
                                color = Color(0xFFE7E7E7),
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                            Box(Modifier.fillMaxWidth().height(2.dp).background(VsCodeAccent))
                        }
                        Box(Modifier.weight(1f))
                        Text(
                            "×",
                            Modifier.clickable(onClick = onCloseBottomPanel)
                                .padding(horizontal = 5.dp),
                            color = Color(0xFFCCCCCC),
                            fontSize = 16.sp
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        terminalDock()
                    }
                }
            }
        }
    }
}
