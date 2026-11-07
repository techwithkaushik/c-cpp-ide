package com.techwithkaushik.cppide.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

data class ExplorerPalette(
    val accent: Color,
    val muted: Color,
    val surface: Color,
    val surfaceRaised: Color
)

@Composable
fun FileExplorerSheet(projectName: String, activeFile: String, files: List<String>, directories: List<String>, onOpenFile: (String) -> Unit, onCreateFile: (String) -> Unit, onCreateFolder: suspend (String) -> Unit, onRenameEntry: (String, String) -> Unit, onDeleteEntry: (String) -> Unit, onMoveEntry: (String, String) -> Unit, onClose: () -> Unit, palette: ExplorerPalette, iconRenderer: @Composable (String, Color, Dp, Modifier) -> Unit, sidebarMode: Boolean = false) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(projectName) { mutableStateOf("") }
    var searchExpanded by rememberSaveable(projectName) { mutableStateOf(false) }
    var fileDialog by rememberSaveable(projectName) { mutableStateOf(false) }
    var folderDialog by rememberSaveable(projectName) { mutableStateOf(false) }
    var newEntryName by rememberSaveable(projectName) { mutableStateOf("") }
    var createTarget by rememberSaveable(projectName) { mutableStateOf(".") }
    var entryMessage by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(projectName) { mutableStateOf(setOf(".")) }
    val matchingFiles = files.filter { query.isBlank() || it.contains(query, true) }
    data class Node(val path: String, val folder: Boolean, val depth: Int)
    val nodes = remember(files, directories, matchingFiles, expanded, query) {
        val output = mutableListOf<Node>()
        fun append(parent: String, depth: Int) {
            val prefix = if (parent == ".") "" else "$parent/"
            directories.filter { it.startsWith(prefix) && it.removePrefix(prefix).isNotBlank() && !it.removePrefix(prefix).contains("/") }
                .filter { query.isBlank() || matchingFiles.any { file -> file.startsWith("$it/") } }
                .sorted().forEach { folder ->
                    output += Node(folder, true, depth)
                    if (query.isNotBlank() || folder in expanded) append(folder, depth + 1)
                }
            matchingFiles.filter { it.startsWith(prefix) && it.removePrefix(prefix).isNotBlank() && !it.removePrefix(prefix).contains("/") }
                .sorted().forEach { output += Node(it, false, depth) }
        }
        append(".", 0); output
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = if (sidebarMode) 4.dp else 14.dp).then(if (sidebarMode) Modifier.verticalScroll(rememberScrollState()) else Modifier), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!sidebarMode) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("EXPLORER", color = Color(0xFFCAD4E0), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(projectName, color = palette.muted, fontSize = 10.sp) }
            IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) { iconRenderer("refresh", palette.muted, 16.dp) }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) { iconRenderer("close", palette.muted, 16.dp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) query = "" }, modifier = Modifier.size(32.dp)) { iconRenderer(if (searchExpanded) "close" else "search", palette.accent, 18.dp) }
            if (searchExpanded) OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.weight(1f).heightIn(min = 40.dp), singleLine = true, shape = androidx.compose.ui.graphics.RectangleShape, placeholder = { Text("Search files", fontSize = 12.sp) })
        }
        ExplorerDirectoryRow(projectName, ".", 0, true, expanded = "." in expanded, onClick = { expanded = if ("." in expanded) emptySet() else setOf(".") }, onCreateFile = { createTarget = it; newEntryName = ""; fileDialog = true }, onCreateFolder = { createTarget = it; newEntryName = ""; folderDialog = true }, onRenameEntry = onRenameEntry, onDeleteEntry = onDeleteEntry, onMoveEntry = onMoveEntry, palette = palette, iconRenderer = iconRenderer)
        if ("." in expanded || query.isNotBlank()) nodes.forEach { node ->
            if (node.folder) ExplorerDirectoryRow(node.path.substringAfterLast("/"), node.path, node.depth + 1, false, expanded = node.path in expanded || query.isNotBlank(), onClick = { expanded = if (node.path in expanded) expanded - node.path else expanded + node.path }, onCreateFile = { createTarget = it; newEntryName = ""; fileDialog = true }, onCreateFolder = { createTarget = it; newEntryName = ""; folderDialog = true }, onRenameEntry = onRenameEntry, onDeleteEntry = onDeleteEntry, onMoveEntry = onMoveEntry, palette, iconRenderer)
            else ExplorerFileRow(node.path.substringAfterLast("/"), node.path, activeFile == node.path, ((node.depth + 1) * 14).dp, { onOpenFile(node.path) }, onRenameEntry, onDeleteEntry, onMoveEntry, palette, iconRenderer)
        }
        if (nodes.isEmpty()) Text(if (query.isBlank()) "This folder is empty" else "No matching files", Modifier.fillMaxWidth().padding(18.dp), color = palette.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (fileDialog || folderDialog) {
            val folder = folderDialog
            val prefix = if (createTarget == ".") "" else "$createTarget/"
            AlertDialog(onDismissRequest = { fileDialog = false; folderDialog = false }, containerColor = palette.surface, title = { Text(if (folder) "New Folder" else "New File", color = Color(0xFFE8EDF5)) },
                text = { Column { Text("In " + if (createTarget == ".") projectName else createTarget, color = palette.muted, fontSize = 10.sp); OutlinedTextField(value = newEntryName, onValueChange = { newEntryName = it }, singleLine = true, label = { Text(if (folder) "Folder name" else "File name") }) } },
                confirmButton = { TextButton(onClick = {
                    val name = newEntryName.trim(); val target = prefix + name
                    when {
                        name.isBlank() || name.contains("/") || name.contains("\\") || name == "." || name == ".." -> entryMessage = "Enter a valid name without path separators."
                        target in files || target in directories -> entryMessage = "An item with this name already exists."
                        folder -> scope.launch { runCatching { onCreateFolder(target) }.onSuccess { expanded = expanded + createTarget + target; entryMessage = "Folder created." }.onFailure { entryMessage = it.message ?: "Unable to create folder." } }
                        else -> { onCreateFile(target); entryMessage = "File created." }
                    }; fileDialog = false; folderDialog = false
                }) { Text("Create", color = palette.accent) } },
                dismissButton = { TextButton(onClick = { fileDialog = false; folderDialog = false }) { Text("Cancel", color = palette.muted) } })
        }
        entryMessage?.let { AlertDialog(onDismissRequest = { entryMessage = null }, containerColor = palette.surface, title = { Text("Explorer") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { entryMessage = null }) { Text("OK") } }) }
    }
}

@Composable
private fun ExplorerDirectoryRow(label: String, path: String, depth: Int, root: Boolean, expanded: Boolean, onClick: () -> Unit, onCreateFile: (String) -> Unit, onCreateFolder: (String) -> Unit, onRenameEntry: (String, String) -> Unit, onDeleteEntry: (String) -> Unit, onMoveEntry: (String, String) -> Unit, palette: ExplorerPalette, iconRenderer: @Composable (String, Color, Dp, Modifier) -> Unit) {
    var menu by remember(path) { mutableStateOf(false) }
    var renameDialog by remember(path) { mutableStateOf(false) }
    var renameValue by remember(path) { mutableStateOf(label) }
    var moveDialog by remember(path) { mutableStateOf(false) }
    var targetDirectory by remember(path) { mutableStateOf(".") }
    Box {
        Row(Modifier.fillMaxWidth().padding(start = (depth * 14).dp).background(if (root) palette.surfaceRaised else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = { menu = true }).height(28.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            iconRenderer(if (expanded) "chevronDown" else "chevronRight", palette.muted, 13.dp); Spacer(Modifier.width(3.dp)); iconRenderer("folder", Color(0xFFE4BE79), 16.dp); Spacer(Modifier.width(7.dp))
            Text(label, Modifier.weight(1f), color = if (root) Color(0xFFE8EDF5) else Color(0xFFDCE3EC), fontSize = 11.sp, fontWeight = if (root) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("New File") }, leadingIcon = { iconRenderer("add", palette.accent, 16.dp) }, onClick = { menu = false; onCreateFile(path) })
            androidx.compose.material3.DropdownMenuItem(text = { Text("New Folder") }, leadingIcon = { iconRenderer("folder", palette.accent, 16.dp) }, onClick = { menu = false; onCreateFolder(path) })
            if (!root) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameDialog = true })
                DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; moveDialog = true })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDeleteEntry(path) })
            }
        }
        if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("Rename folder") }, text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { renameDialog = false; onRenameEntry(path, renameValue.trim()) }) { Text("Rename") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } })
        if (moveDialog) AlertDialog(onDismissRequest = { moveDialog = false }, title = { Text("Move folder") }, text = { OutlinedTextField(value = targetDirectory, onValueChange = { targetDirectory = it }, singleLine = true, placeholder = { Text(". or src") }) }, confirmButton = { TextButton(onClick = { moveDialog = false; onMoveEntry(path, targetDirectory.trim()) }) { Text("Move") } }, dismissButton = { TextButton(onClick = { moveDialog = false }) { Text("Cancel") } })
    }
}
@Composable
private fun FileTypeIcon(
    fileName: String,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val file = fileName.substringAfterLast('/').lowercase()
    val (label, color) = when {
        file == "cmakelists.txt" || file == "makefile" -> "⚙" to Color(0xFFB8A1E3)
        ext == "c" -> "C" to Color(0xFF659AD2)
        ext == "cpp" || ext == "cc" || ext == "cxx" -> "C++" to Color(0xFF9CBEFF)
        ext == "h" || ext == "hpp" || ext == "hh" -> "H" to Color(0xFFE5B978)
        ext == "json" -> "{}" to Color(0xFFE5B978)
        ext == "xml" -> "X" to Color(0xFFE58A75)
        ext == "kt" || ext == "kts" -> "K" to Color(0xFFB8A1E3)
        ext == "java" -> "J" to Color(0xFFE5A16B)
        ext == "py" -> "Py" to Color(0xFF82B7E8)
        ext == "rs" -> "Rs" to Color(0xFFE5A16B)
        ext == "js" || ext == "mjs" -> "JS" to Color(0xFFE5CB70)
        ext == "ts" || ext == "tsx" -> "TS" to Color(0xFF659AD2)
        ext == "html" || ext == "htm" -> "5" to Color(0xFFE58A75)
        ext == "css" -> "#" to Color(0xFF82B7E8)
        ext == "md" -> "M↓" to Color(0xFF9AA8BA)
        ext == "sh" || ext == "bash" -> "$" to Color(0xFF7DDBB0)
        ext == "yaml" || ext == "yml" -> "Y" to Color(0xFFE58A75)
        ext == "toml" || ext == "ini" -> "⚙" to Color(0xFFB8A1E3)
        ext == "txt" || ext == "log" -> "≡" to Color(0xFF9AA8BA)
        else -> "·" to Color(0xFF9AA8BA)
    }
    Box(
        modifier = modifier.size(iconSize).background(color.copy(alpha = 0.13f), androidx.compose.ui.graphics.RectangleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = color, fontSize = if (label.length > 2) 7.sp else if (label.length == 2) 9.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ExplorerFileRow(
    name: String,
    description: String,
    selected: Boolean,
    indent: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onRenameEntry: (String, String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onMoveEntry: (String, String) -> Unit,
    palette: ExplorerPalette,
    iconRenderer: @Composable (String, Color, Dp, Modifier) -> Unit
) {
    var menu by remember(description) { mutableStateOf(false) }
    var renameDialog by remember(description) { mutableStateOf(false) }
    var renameValue by remember(description) { mutableStateOf(name) }
    var moveDialog by remember(description) { mutableStateOf(false) }
    var targetDirectory by remember(description) { mutableStateOf(".") }
    Box {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = indent)
            .background(if (selected) Color(0xFF1D3047) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })
            .height(28.dp)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileTypeIcon(name, 15.dp, Modifier.width(22.dp))
        Text(name, Modifier.weight(1f), color = if (selected) palette.accent else Color(0xFFE8EDF5), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Text("●", color = palette.accent, fontSize = 8.sp)
    }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renameDialog = true })
        DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; moveDialog = true })
        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDeleteEntry(description) })
    }
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("Rename file") }, text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { renameDialog = false; onRenameEntry(description, renameValue.trim()) }) { Text("Rename") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } })
    if (moveDialog) AlertDialog(onDismissRequest = { moveDialog = false }, title = { Text("Move file") }, text = { Column { Text("Destination folder", color = palette.muted); OutlinedTextField(value = targetDirectory, onValueChange = { targetDirectory = it }, singleLine = true, placeholder = { Text(". or src/include") }) } }, confirmButton = { TextButton(onClick = { moveDialog = false; onMoveEntry(description, targetDirectory.trim()) }) { Text("Move") } }, dismissButton = { TextButton(onClick = { moveDialog = false }) { Text("Cancel") } })
    }
}

