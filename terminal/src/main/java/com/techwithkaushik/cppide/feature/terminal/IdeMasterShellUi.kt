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
import com.techwithkaushik.cppide.feature.terminal.ui.components.EditorBuffer
import com.techwithkaushik.cppide.feature.terminal.ui.components.MultiTabEditor
import com.techwithkaushik.cppide.feature.terminal.ui.components.OutlineNode
import com.techwithkaushik.cppide.feature.terminal.ui.components.UbuntuEnvironmentState
import com.techwithkaushik.cppide.feature.terminal.ui.components.WorkspaceNode
import com.techwithkaushik.cppide.feature.terminal.ui.components.TerminalDock
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
    statusBarContent: @Composable () -> Unit = {},
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
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().background(VsCodeBackground)) {
        var dockHeight by remember(maxHeight) { mutableStateOf(maxHeight / 3f) }
        val maxDock = (maxHeight - 40.dp).coerceAtLeast(0.dp)
        val minDock = 0.dp
        val boundedMaxDock = maxDock.coerceAtLeast(minDock)
        val actualDockHeight = dockHeight.coerceIn(minDock, boundedMaxDock)
        val dockVisible = bottomPanelVisible && actualDockHeight > 0.dp

        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(VsCodeBackground)) {
                MultiTabEditor(
                    buffers = editorBuffers,
                    activeBufferId = activeEditorBufferId,
                    onSelectBuffer = onSelectEditorBuffer,
                    editorContent = { workspaceContent() }
                )
            }
            TerminalDock(
                selectedPanel = selectedPanel,
                expanded = dockVisible,
                onSelectPanel = onSelectPanel,
                onClose = onCloseBottomPanel,
                onHeightDelta = { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp() }
                    dockHeight = (dockHeight - deltaDp).coerceIn(minDock, boundedMaxDock)
                },
                content = terminalDock,
                modifier = Modifier.fillMaxWidth().height(30.dp + if (dockVisible) 10.dp + actualDockHeight else 0.dp)
            )
            statusBarContent()
        }
    }
}
