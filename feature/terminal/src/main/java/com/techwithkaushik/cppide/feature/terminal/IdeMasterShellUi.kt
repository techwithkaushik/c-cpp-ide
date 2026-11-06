package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.techwithkaushik.cppide.feature.terminal.ui.components.TerminalDock
import com.techwithkaushik.cppide.feature.terminal.ui.theme.VsCodeBackground

/**
 * Root workspace partition. The terminal is constrained to the measured dock
 * bounds and the PTY view receives live size changes while the divider is dragged.
 */
@Composable
fun IdeMasterShellUi(
    modifier: Modifier = Modifier,
    bottomPanelVisible: Boolean,
    onCloseBottomPanel: () -> Unit,
    workspaceContent: @Composable () -> Unit,
    selectedPanel: String,
    onSelectPanel: (String) -> Unit,
    terminalDock: @Composable () -> Unit,
) {
    var dockHeight by remember { mutableStateOf(280.dp) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize().background(VsCodeBackground)) {
        val maxDock = maxHeight * 0.92f
        val minDock = 112.dp.coerceAtMost(maxHeight)
        val actualDockHeight = dockHeight.coerceIn(minDock, maxDock.coerceAtLeast(minDock))
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(VsCodeBackground)) {
                workspaceContent()
            }
            if (bottomPanelVisible) {
                TerminalDock(
                    selectedPanel = selectedPanel,
                    onSelectPanel = onSelectPanel,
                    onClose = onCloseBottomPanel,
                    onHeightDelta = { delta ->
                        dockHeight = (dockHeight - with(density) { delta.toDp() })
                            .coerceIn(minDock, maxDock.coerceAtLeast(minDock))
                    },
                    content = terminalDock,
                    modifier = Modifier.fillMaxWidth().height(actualDockHeight)
                )
            }
        }
    }
}
