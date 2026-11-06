package com.techwithkaushik.cppide.feature.terminal.ui.components

import androidx.compose.animation.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.feature.terminal.ui.theme.*

data class WorkspaceNode(val name: String, val depth: Int = 0, val directory: Boolean = false, val selected: Boolean = false)
data class OutlineNode(val name: String, val depth: Int = 0, val symbol: String = "ƒ")
data class UbuntuEnvironmentState(
    val distribution: String = "Ubuntu",
    val status: String = "Runtime status unavailable",
    val rootfsPath: String = "",
    val onStart: (() -> Unit)? = null,
    val onStop: (() -> Unit)? = null
)

@Composable
fun SidebarPanel(
    expanded: Boolean,
    title: String,
    workspaceNodes: List<WorkspaceNode>,
    outlineNodes: List<OutlineNode>,
    ubuntu: UbuntuEnvironmentState,
    onWorkspaceNodeClick: (WorkspaceNode) -> Unit,
    onOutlineNodeClick: (OutlineNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val width = animateDpAsState(if (expanded) 260.dp else 0.dp, label = "sidebar-width")
    Column(
        modifier.width(width.value).fillMaxHeight().background(VsCodeSidebar)
    ) {
        if (width.value > 0.dp) {
            Text(title.uppercase(), Modifier.fillMaxWidth().padding(12.dp), color = VsCodeText, fontSize = 11.sp)
            when (title) {
                "Ubuntu" -> {
                    Text(ubuntu.distribution, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = VsCodeText)
                    Text(ubuntu.status, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = VsCodeMutedText, fontSize = 11.sp)
                    if (ubuntu.rootfsPath.isNotBlank()) Text(ubuntu.rootfsPath, Modifier.padding(12.dp), color = VsCodeMutedText, fontSize = 10.sp)
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ubuntu.onStart?.let { Text("Start", Modifier.clickable(onClick = it).padding(6.dp), color = VsCodeAccent) }
                        ubuntu.onStop?.let { Text("Stop", Modifier.clickable(onClick = it).padding(6.dp), color = VsCodeText) }
                    }
                }
                "Outline" -> outlineNodes.forEach { node ->
                    Text("${"  ".repeat(node.depth)}${node.symbol}  ${node.name}",
                        Modifier.fillMaxWidth().clickable { onOutlineNodeClick(node) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = VsCodeText, fontSize = 12.sp)
                }
                else -> workspaceNodes.forEach { node ->
                    Text("${"  ".repeat(node.depth)}${if (node.directory) "▸ " else "  "}${node.name}",
                        Modifier.fillMaxWidth().background(if (node.selected) VsCodeBorder else VsCodeSidebar)
                            .clickable { onWorkspaceNodeClick(node) }.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = VsCodeText, fontSize = 12.sp)
                }
            }
        }
    }
}
