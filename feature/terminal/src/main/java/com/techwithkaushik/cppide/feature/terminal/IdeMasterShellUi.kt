package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.techwithkaushik.cppide.feature.terminal.ui.components.ActivityBar
import com.techwithkaushik.cppide.feature.terminal.ui.components.ActivityDestination
import com.techwithkaushik.cppide.feature.terminal.ui.components.EditorBuffer
import com.techwithkaushik.cppide.feature.terminal.ui.components.MultiTabEditor
import com.techwithkaushik.cppide.feature.terminal.ui.components.OutlineNode
import com.techwithkaushik.cppide.feature.terminal.ui.components.SidebarPanel
import com.techwithkaushik.cppide.feature.terminal.ui.components.TerminalDock
import com.techwithkaushik.cppide.feature.terminal.ui.components.UbuntuEnvironmentState
import com.techwithkaushik.cppide.feature.terminal.ui.components.WorkspaceNode
import com.techwithkaushik.cppide.feature.terminal.ui.theme.VsCodeBackground

/**
 * Root IDE shell. The workspace/editor, navigation rail, contextual sidebar and
 * resizable terminal are independent layout sections with explicit boundaries.
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
    workspaceNodes: List<WorkspaceNode> = emptyList(),
    outlineNodes: List<OutlineNode> = emptyList(),
    ubuntuEnvironment: UbuntuEnvironmentState = UbuntuEnvironmentState(),
    onWorkspaceNodeClick: (WorkspaceNode) -> Unit = {},
    onOutlineNodeClick: (OutlineNode) -> Unit = {},
    editorBuffers: List<EditorBuffer> = emptyList(),
    activeEditorBufferId: String? = null,
    onSelectEditorBuffer: (EditorBuffer) -> Unit = {},
) {
    var dockHeight by remember { mutableStateOf(280.dp) }
    var selectedDestination by remember { mutableStateOf(ActivityDestination.EXPLORER) }
    var sidebarExpanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().background(VsCodeBackground)) {
        val maxDock = maxHeight * 0.92f
        val minDock = 112.dp.coerceAtMost(maxHeight)
        val boundedMaxDock = maxDock.coerceAtLeast(minDock)
        val actualDockHeight = dockHeight.coerceIn(minDock, boundedMaxDock)

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ActivityBar(
                    selected = selectedDestination,
                    onSelect = { destination ->
                        if (selectedDestination == destination && sidebarExpanded) {
                            sidebarExpanded = false
                        } else {
                            selectedDestination = destination
                            sidebarExpanded = true
                        }
                    }
                )
                SidebarPanel(
                    expanded = sidebarExpanded,
                    title = when (selectedDestination) {
                        ActivityDestination.EXPLORER -> "Explorer"
                        ActivityDestination.SEARCH -> "Search"
                        ActivityDestination.OUTLINE -> "Outline"
                        ActivityDestination.UBUNTU -> "Ubuntu"
                    },
                    workspaceNodes = workspaceNodes,
                    outlineNodes = outlineNodes,
                    ubuntu = ubuntuEnvironment,
                    onWorkspaceNodeClick = onWorkspaceNodeClick,
                    onOutlineNodeClick = onOutlineNodeClick
                )
                Box(Modifier.weight(1f).fillMaxSize().background(VsCodeBackground)) {
                    MultiTabEditor(
                        buffers = editorBuffers,
                        activeBufferId = activeEditorBufferId,
                        onSelectBuffer = onSelectEditorBuffer,
                        editorContent = { workspaceContent() }
                    )
                }
            }
            if (bottomPanelVisible) {
                TerminalDock(
                    selectedPanel = selectedPanel,
                    onSelectPanel = onSelectPanel,
                    onClose = onCloseBottomPanel,
                    onHeightDelta = { deltaPx ->
                        val deltaDp = with(density) { deltaPx.toDp() }
                        dockHeight = (dockHeight - deltaDp).coerceIn(minDock, boundedMaxDock)
                    },
                    content = terminalDock,
                    modifier = Modifier.fillMaxWidth().height(actualDockHeight)
                )
            }
        }
    }
}
