package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.techwithkaushik.cppide.feature.terminal.ui.components.TerminalDock
import com.techwithkaushik.cppide.feature.terminal.ui.theme.VsCodeBackground
import com.techwithkaushik.cppide.feature.terminal.ui.theme.VsCodeTerminal

/**
 * Root workspace partition. Terminal content is measured by AndroidView at its
 * final bounded size; drag updates the same measured partition rather than a sheet.
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
    BoxWithConstraints(modifier.fillMaxSize().background(VsCodeBackground)) {
        val maxDock = maxHeight * 0.92f
        val actualDockHeight = dockHeight.coerceIn(112.dp, maxDock.coerceAtLeast(112.dp))
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
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        dockHeight = (dockHeight - with(density) { delta.toDp() })
                            .coerceIn(112.dp, maxDock.coerceAtLeast(112.dp))
                    },
                    content = terminalDock,
                    modifier = Modifier.fillMaxWidth().height(actualDockHeight)
                )
            }
        }
    }
}
