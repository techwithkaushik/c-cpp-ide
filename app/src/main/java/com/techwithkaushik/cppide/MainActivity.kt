package com.techwithkaushik.cppide

import android.os.Bundle
import com.techwithkaushik.cppide.feature.editor.SoraCodeEditor
import com.techwithkaushik.cppide.feature.editor.SoraEditorController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val IdeBackground = Color(0xFF101820)
private val IdeSurface = Color(0xFF192733)
private val IdeSurfaceRaised = Color(0xFF243D52)
private val IdeAccent = Color(0xFF659AD2)
private val IdeMuted = Color(0xFF9AA8BA)
private val IdeGreen = Color(0xFF7DDBB0)

private enum class IdeScreen { Welcome, Setup, Projects, Workspace }
private enum class ToolPanel { Terminal, Build, Problems, Output }

private data class IdeProject(val name: String, val language: String, val path: String, val updated: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary = IdeAccent,
                    onPrimary = Color(0xFF071426),
                    background = IdeBackground,
                    surface = IdeSurface,
                    onSurface = Color(0xFFE8EDF5),
                    onSurfaceVariant = IdeMuted,
                    secondary = IdeGreen
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = IdeBackground) {
                    IdeUiPrototype()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeUiPrototype() {
    var screen by rememberSaveable { mutableStateOf(IdeScreen.Welcome) }
    var setupChoice by rememberSaveable { mutableStateOf("Download Ubuntu from Internet") }
    var selectedProject by rememberSaveable { mutableStateOf("Hello C") }
    var activeFile by rememberSaveable { mutableStateOf("main.c") }
    var code by rememberSaveable {
        mutableStateOf("#include <stdio.h>\n\nint main(void) {\n    printf(\"Hello, world!\\n\");\n    return 0;\n}")
    }
    val openFiles = remember { mutableStateListOf("main.c") }
    val dirtyFiles = remember { mutableStateListOf<String>() }
    val fileContents = remember { mutableStateMapOf("main.c" to code) }
    var newProjectDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCloseFile by rememberSaveable { mutableStateOf<String?>(null) }
    var newProjectName by rememberSaveable { mutableStateOf("") }
    var showToolPanel by rememberSaveable { mutableStateOf(false) }
    var showExplorer by rememberSaveable { mutableStateOf(true) }
    var activeActivity by rememberSaveable { mutableStateOf("Explorer") }
    var showProjectOverview by rememberSaveable { mutableStateOf(false) }
    var showRecentFiles by rememberSaveable { mutableStateOf(false) }
    var recentFiles = remember { mutableStateListOf<String>() }
    fun openFile(file: String) {
        if (!openFiles.contains(file)) openFiles.add(file)
        activeFile = file
        code = fileContents[file] ?: sampleCode(file).also { fileContents[file] = it }
        recentFiles.remove(file)
        recentFiles.add(0, file)
    }
    fun closeFile(file: String) {
        if (dirtyFiles.contains(file)) { pendingCloseFile = file; return }
        openFiles.remove(file)
        if (openFiles.isEmpty()) openFiles.add(if (file == "main.c") "main.cpp" else "main.c")
        if (activeFile == file) {
            val next = openFiles.last()
            activeFile = next
            code = fileContents[next] ?: sampleCode(next).also { fileContents[next] = it }
        }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    var commandQuery by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchInProject by rememberSaveable { mutableStateOf(false) }
    var editorFontSize by rememberSaveable { mutableStateOf(13f) }
    var editorWordWrap by rememberSaveable { mutableStateOf(false) }
    var selectedPanel by rememberSaveable { mutableStateOf(ToolPanel.Terminal) }
    var drawerDestination by rememberSaveable { mutableStateOf("File Explorer") }
    val projects = remember {
        mutableStateListOf(
            IdeProject("Hello C", "C17", "Projects/Hello-C", "Just now"),
            IdeProject("Cpp Playground", "C++20", "Projects/Cpp-Playground", "Yesterday")
        )
    }
    val editorController = remember { SoraEditorController() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    pendingCloseFile?.let { fileToClose ->
        AlertDialog(
            onDismissRequest = { pendingCloseFile = null },
            containerColor = IdeSurface,
            title = { Text("Unsaved changes", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
            text = { Text("Save changes to $fileToClose before closing it?", color = IdeMuted) },
            confirmButton = {
                TextButton(onClick = {
                    if (fileToClose == activeFile) fileContents[fileToClose] = code
                    dirtyFiles.remove(fileToClose)
                    pendingCloseFile = null
                    closeFile(fileToClose)
                }) { Text("Save", color = IdeAccent) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        dirtyFiles.remove(fileToClose)
                        pendingCloseFile = null
                        closeFile(fileToClose)
                    }) { Text("Discard", color = IdeMuted) }
                    TextButton(onClick = { pendingCloseFile = null }) { Text("Cancel", color = IdeAccent) }
                }
            }
        )
    }

    if (screen != IdeScreen.Workspace) {
        when (screen) {
            IdeScreen.Welcome -> WelcomeScreen(
                onNext = { screen = IdeScreen.Setup }
            )
            IdeScreen.Setup -> SetupScreen(
                selected = setupChoice,
                onSelect = { setupChoice = it },
                onContinue = { screen = IdeScreen.Projects },
                onBack = { screen = IdeScreen.Welcome }
            )
            IdeScreen.Projects -> ProjectListScreen(
                projects = projects,
                onOpen = { project -> selectedProject = project.name; openFile(if (project.language == "C++20") "main.cpp" else "main.c"); screen = IdeScreen.Workspace },
                onNew = { newProjectDialog = true },
                onSettings = { screen = IdeScreen.Setup }
            )
            else -> Unit
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = IdeSurface,
                    drawerContentColor = Color(0xFFE8EDF5)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("C/C++ IDE", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = IdeAccent)
                        Spacer(Modifier.height(4.dp))
                        Text(selectedProject, color = IdeMuted, fontSize = 13.sp)
                    }
                    Divider(color = IdeSurfaceRaised)
                    listOf("File Explorer", "Terminal", "Build / Problems", "Project Overview", "Recent Files", "Settings", "Runtime Setup").forEach { item ->
                        val active = drawerDestination == item
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                drawerDestination = item
                                when (item) {
                                    "File Explorer" -> showExplorer = true
                                    "Project Overview" -> showProjectOverview = true
                                    "Recent Files" -> showRecentFiles = true
                                    "Terminal" -> { selectedPanel = ToolPanel.Terminal; showToolPanel = true }
                                    "Build / Problems" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                                    "Runtime Setup" -> screen = IdeScreen.Setup
                                    "Settings" -> showSettings = true
                                }
                                scope.launch { drawerState.close() }
                            }.background(if (active) IdeSurfaceRaised else Color.Transparent)
                                .padding(horizontal = 20.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IdeIcon(
                                when (item) {
                                    "File Explorer" -> "folder"
                                    "Terminal" -> "terminal"
                                    "Build / Problems" -> "problems"
                                    "Project Overview" -> "home"
                                    "Recent Files" -> "history"
                                    "Settings" -> "settings"
                                    else -> "build"
                                },
                                if (active) IdeAccent else IdeMuted, 20.dp, Modifier.width(32.dp)
                            )
                            Text(item, color = if (active) IdeAccent else Color(0xFFE8EDF5))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("UI prototype • runtime not connected", color = IdeMuted, fontSize = 11.sp, modifier = Modifier.padding(18.dp))
                }
            }
        ) {
            Row(Modifier.fillMaxSize().background(IdeBackground)) {
                // VS Code-inspired Activity Bar: compact, icon-first navigation suited to phone screens.
                Column(
                    Modifier.width(48.dp).fillMaxSize().background(Color(0xFF111820)).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    listOf(
                        Triple("Explorer", "folder", "Open project files"),
                        Triple("Search", "search", "Search in project"),
                        Triple("Source Control", "git", "Source control"),
                        Triple("Run and Debug", "run", "Build and run"),
                        Triple("Extensions", "extensions", "Extensions")
                    ).forEach { (label, icon, hint) ->
                        val selected = activeActivity == label
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    activeActivity = label
                                    when (label) {
                                        "Explorer" -> showExplorer = !showExplorer
                                        "Search" -> { searchInProject = true; searchQuery = ""; showSearch = true }
                                        "Run and Debug" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                                        "Source Control" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                                        "Extensions" -> showSettings = true
                                    }
                                }
                                .background(if (selected) Color(0xFF202B37) else Color.Transparent)
                                .padding(vertical = 11.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (selected) Box(
                                    Modifier.align(Alignment.CenterStart).width(2.dp).height(30.dp).background(IdeAccent)
                                )
                                IdeIcon(icon, if (selected) Color(0xFFE8EDF5) else IdeMuted, 22.dp)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth().clickable { showSettings = true }.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { IdeIcon("settings", IdeMuted, 22.dp) }
                }
                if (showExplorer) {
                    Column(
                        Modifier.width(248.dp).fillMaxSize().background(IdeSurface)
                    ) {
                        FileExplorerSheet(
                            projectName = selectedProject,
                            activeFile = activeFile,
                            onOpenFile = { openFile(it) },
                            onClose = { showExplorer = false },
                            sidebarMode = true
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    WorkspaceScreen(
                        projectName = selectedProject,
                        activeFile = activeFile,
                        code = code,
                        openFiles = openFiles,
                        dirtyFiles = dirtyFiles,
                        onCodeChange = { updated -> code = updated; fileContents[activeFile] = updated; if (!dirtyFiles.contains(activeFile)) dirtyFiles.add(activeFile) },
                        onSelectFile = { openFile(it) },
                        onCloseFile = { closeFile(it) },
                        editorController = editorController,
                        editorFontSize = editorFontSize,
                        editorWordWrap = editorWordWrap,
                        onMenu = { scope.launch { drawerState.open() } },
                        onExplorer = { showExplorer = !showExplorer },
                        onFile = { file -> openFile(file) },
                        onAction = { action ->
                            when (action) {
                                "Command Palette" -> { commandQuery = ""; showCommandPalette = true }
                                "Terminal" -> { selectedPanel = ToolPanel.Terminal; showToolPanel = true }
                                "Build" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                                "Run" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                                "Problems" -> { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                                "Save" -> { dirtyFiles.remove(activeFile); fileContents[activeFile] = code }
                                "Undo" -> editorController.undo()
                                "Redo" -> editorController.redo()
                                "Find" -> { searchInProject = false; searchQuery = ""; showSearch = true }
                                "Find in Project" -> { searchInProject = true; searchQuery = ""; showSearch = true }
                                "Save All" -> { openFiles.forEach { file -> if (file == activeFile) fileContents[file] = code }; dirtyFiles.clear() }
                                "Settings" -> showSettings = true
                                "File Explorer" -> showExplorer = !showExplorer
                                "Sync Project" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                                "Cancel Build" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                            }
                        },
                        selectedPanel = selectedPanel,
                        onPanel = { selectedPanel = it; showToolPanel = true }
                    )
                }
            }
        }
        if (showCommandPalette) {
            val commands = listOf(
                "New File", "Find", "Find in Project", "Save", "Save All",
                "Build", "Run", "Terminal", "Problems", "Output",
                "File Explorer", "Settings", "Sync Project"
            )
            val matches = commands.filter { it.contains(commandQuery, ignoreCase = true) }
            AlertDialog(
                onDismissRequest = { showCommandPalette = false },
                containerColor = Color(0xFF1B2633),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Command Palette", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = commandQuery,
                            onValueChange = { commandQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("Type a command…") },
                            leadingIcon = { IdeIcon("search", IdeAccent, 18.dp) }
                        )
                    }
                },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                        if (matches.isEmpty()) {
                            Text("No matching commands", color = IdeMuted, modifier = Modifier.padding(12.dp))
                        }
                        matches.forEach { command ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        showCommandPalette = false
                                        when (command) {
                                            "New File" -> onFile(if (activeFile == "main.c") "main.cpp" else "utils.h")
                                            "File Explorer" -> showExplorer = !showExplorer
                                            "Terminal", "Build", "Problems", "Output" -> onAction(command)
                                            else -> onAction(command)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IdeIcon(
                                    when (command) {
                                        "Terminal" -> "terminal"
                                        "Build", "Run" -> "build"
                                        "Problems" -> "problems"
                                        "File Explorer", "New File" -> "folder"
                                        "Settings" -> "settings"
                                        else -> "command"
                                    }, IdeAccent, 18.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(command, color = Color(0xFFE8EDF5), fontSize = 14.sp)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    when (command) {
                                        "Save" -> "Ctrl+S"
                                        "Find" -> "Ctrl+F"
                                        "Command Palette" -> "Ctrl+Shift+P"
                                        else -> ""
                                    }, color = IdeMuted, fontSize = 10.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCommandPalette = false }) { Text("Close", color = IdeAccent) }
                }
            )
        }
        if (showSearch) {
            val pattern = remember(searchQuery) { if (searchQuery.isBlank()) null else Regex(Regex.escape(searchQuery), RegexOption.IGNORE_CASE) }
            val projectResults = if (searchInProject && pattern != null) {
                fileContents.toMap().mapNotNull { (file, contents) ->
                    val count = pattern.findAll(if (file == activeFile) code else contents).count()
                    if (count > 0) file to count else null
                }.sortedBy { it.first }
            } else emptyList()
            val currentMatches = pattern?.findAll(code)?.count() ?: 0
            val matchCount = if (searchInProject) projectResults.sumOf { it.second } else currentMatches
            AlertDialog(
                onDismissRequest = { showSearch = false },
                containerColor = IdeSurface,
                title = { Text(if (searchInProject) "Find in project" else "Find in file", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search text") }, leadingIcon = { IdeIcon("search", IdeAccent, 18.dp) })
                        Text(if (searchQuery.isBlank()) "Type to search " + if (searchInProject) "all project files" else "the current file" else "$matchCount match${if (matchCount == 1) "" else "es"} found", color = IdeMuted, fontSize = 12.sp)
                        if (searchInProject && pattern != null) {
                            if (projectResults.isEmpty()) Text("No matches in project files.", color = IdeMuted, fontSize = 12.sp)
                            else LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(projectResults, key = { it.first }) { (file, count) ->
                                    Row(Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(9.dp)).clickable { openFile(file); showSearch = false }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FileTypeIcon(file, 19.dp)
                                        Spacer(Modifier.width(9.dp))
                                        Text(file, Modifier.weight(1f), color = Color(0xFFE8EDF5), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("$count", color = IdeGreen, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSearch = false }) { Text("Done", color = IdeAccent) } },
                dismissButton = { TextButton(onClick = { searchQuery = "" }) { Text("Clear", color = IdeMuted) } }
            )
        }
        if (showSettings) {
            var settingsCategory by rememberSaveable { mutableStateOf("Editor") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = IdeSurface,
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Settings", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold)
                        Text("Configure your coding workspace", color = IdeMuted, fontSize = 12.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Editor", "C/C++", "Build", "Appearance").forEach { category ->
                                if (settingsCategory == category) {
                                    Button(onClick = { settingsCategory = category }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 7.dp)) { Text(category, fontSize = 11.sp) }
                                } else {
                                    OutlinedButton(onClick = { settingsCategory = category }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 7.dp)) { Text(category, fontSize = 11.sp) }
                                }
                            }
                        }
                        Divider(color = IdeSurfaceRaised)
                        when (settingsCategory) {
                            "Editor" -> {
                                Text("EDITOR", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Font size: ${editorFontSize.toInt()} sp", color = Color(0xFFE8EDF5))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { editorFontSize = (editorFontSize - 1f).coerceAtLeast(10f) }) { Text("A− Smaller") }
                                    OutlinedButton(onClick = { editorFontSize = (editorFontSize + 1f).coerceAtMost(24f) }) { Text("A+ Larger") }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Word wrap", color = Color(0xFFE8EDF5))
                                        Text("Wrap long source lines", color = IdeMuted, fontSize = 12.sp)
                                    }
                                    androidx.compose.material3.Switch(checked = editorWordWrap, onCheckedChange = { editorWordWrap = it })
                                }
                                SettingsInfoRow("Editor engine", "Sora code editor")
                            }
                            "C/C++" -> {
                                Text("LANGUAGE", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                SettingsInfoRow("Supported source", "C (.c), C++ (.cpp)")
                                SettingsInfoRow("Header files", ".h / .hpp")
                                SettingsInfoRow("Language standard", "C17 / C++20 (preview)")
                                SettingsInfoRow("Language server", "clangd — not connected")
                            }
                            "Build" -> {
                                Text("BUILD TOOLCHAIN", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                SettingsInfoRow("Compiler", "Clang / LLVM — not connected")
                                SettingsInfoRow("Build system", "CMake — not connected")
                                SettingsInfoRow("Execution target", "Linux environment")
                                Text("Toolchain options are informational until runtime integration is implemented.", color = Color(0xFFE5B978), fontSize = 11.sp)
                            }
                            else -> {
                                Text("WORKSPACE", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                SettingsInfoRow("Theme", "Dark IDE")
                                SettingsInfoRow("Accent", "Blue")
                                SettingsInfoRow("Project", selectedProject)
                                Text("Appearance controls will be expanded in a later UI pass.", color = IdeMuted, fontSize = 11.sp)
                            }
                        }
                    }
                },
                confirmButton = { Button(onClick = { showSettings = false }) { Text("Done") } }
            )
        }
        if (showProjectOverview) {
            ModalBottomSheet(
                onDismissRequest = { showProjectOverview = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = IdeSurface,
                contentWindowInsets = { WindowInsets.navigationBars }
            ) {
                ProjectOverviewSheet(
                    projectName = selectedProject,
                    projectLanguage = projects.firstOrNull { it.name == selectedProject }?.language ?: "C17",
                    onOpenFile = { file ->
                        openFile(file)
                        showProjectOverview = false
                    },
                    onBrowseFiles = { showProjectOverview = false; showExplorer = true },
                    onClose = { showProjectOverview = false }
                )
            }
        }
        if (showRecentFiles) {
            ModalBottomSheet(
                onDismissRequest = { showRecentFiles = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = IdeSurface,
                contentWindowInsets = { WindowInsets.navigationBars }
            ) {
                RecentFilesSheet(
                    files = recentFiles.toList(),
                    activeFile = activeFile,
                    onOpenFile = { file ->
                        openFile(file)
                        showRecentFiles = false
                    },
                    onClear = { recentFiles.clear() },
                    onClose = { showRecentFiles = false }
                )
            }
        }
        if (showToolPanel) {
            ModalBottomSheet(
                onDismissRequest = { showToolPanel = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = IdeSurface,
                contentWindowInsets = { WindowInsets.navigationBars }
            ) {
                ToolPanelContent(
                    selected = selectedPanel,
                    onSelect = { selectedPanel = it },
                    onClose = { showToolPanel = false }
                )
            }
        }
    }

    if (newProjectDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { newProjectDialog = false; newProjectName = "" },
            containerColor = IdeSurface,
            title = { Text("Create project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Project name") },
                        singleLine = true
                    )
                    Text("Choose the project type on the next setup step.", color = IdeMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val name = newProjectName.trim().ifBlank { "New C Project" }
                    projects.add(0, IdeProject(name, "C17", "Projects/$name", "Just now"))
                    selectedProject = name
                    activeFile = "main.c"
                    code = sampleCode("main.c")
                    newProjectDialog = false
                    newProjectName = ""
                    screen = IdeScreen.Workspace
                }) { Text("Create") }
            },
            dismissButton = {
                OutlinedButton(onClick = { newProjectDialog = false; newProjectName = "" }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProjectOverviewSheet(
    projectName: String,
    projectLanguage: String,
    onOpenFile: (String) -> Unit,
    onBrowseFiles: () -> Unit,
    onClose: () -> Unit
) {
    val isCpp = projectLanguage.startsWith("C++")
    val primaryFile = if (isCpp) "main.cpp" else "main.c"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Project Overview", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("PROJECT SUMMARY", color = IdeMuted, fontSize = 10.sp)
            }
            Text("Done", Modifier.clickable(onClick = onClose).padding(8.dp), color = IdeAccent)
        }
        Card(colors = CardDefaults.cardColors(containerColor = IdeSurfaceRaised), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(projectName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Projects/$projectName", color = IdeMuted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(projectLanguage, color = IdeAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Text("• Sample workspace", color = IdeMuted, fontSize = 11.sp)
                }
            }
        }
        Text("PROJECT CONTENTS", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverviewMetric("3", "Sample files", Modifier.weight(1f))
            OverviewMetric("2", "Source files", Modifier.weight(1f))
            OverviewMetric("1", "Header", Modifier.weight(1f))
        }
        Text("Quick open", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold)
        OverviewFileRow(primaryFile, if (isCpp) "C++ source · entry point" else "C source · entry point") { onOpenFile(primaryFile) }
        OverviewFileRow(if (isCpp) "main.c" else "main.cpp", "Alternative language example") { onOpenFile(if (isCpp) "main.c" else "main.cpp") }
        OverviewFileRow("utils.h", "Shared header declaration") { onOpenFile("utils.h") }
        OutlinedButton(onClick = onBrowseFiles, modifier = Modifier.fillMaxWidth()) { Text("Browse project files") }
        Text("Preview only: files and metadata are not yet loaded from disk.", color = Color(0xFFE5B978), fontSize = 11.sp)
    }
}

@Composable
private fun OverviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(IdeSurfaceRaised, RoundedCornerShape(10.dp)).padding(vertical = 13.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(value, color = IdeAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = IdeMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun OverviewFileRow(name: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (name.endsWith(".h")) "◇" else if (name.endsWith(".cpp")) "C++" else "C",
            color = IdeAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(38.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color(0xFFE8EDF5), fontSize = 13.sp)
            Text(description, color = IdeMuted, fontSize = 10.sp)
        }
        Text("Open ›", color = IdeAccent, fontSize = 11.sp)
    }
}

@Composable
private fun RecentFilesSheet(
    files: List<String>,
    activeFile: String,
    onOpenFile: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    val displayFiles = if (files.isEmpty()) listOf(activeFile) else files
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recent Files", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${displayFiles.distinct().size} recently opened · this session", color = IdeMuted, fontSize = 11.sp)
            }
            if (files.isNotEmpty()) Text("Clear", Modifier.clickable(onClick = onClear).padding(8.dp), color = IdeAccent)
            Text("Done", Modifier.clickable(onClick = onClose).padding(8.dp), color = IdeMuted)
        }
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("◷", color = IdeAccent, fontSize = 36.sp)
                Text("No recent files yet", fontWeight = FontWeight.SemiBold)
                Text("Files you open will appear here.", color = IdeMuted, fontSize = 12.sp)
                OutlinedButton(onClick = { onOpenFile(activeFile) }) { Text("Return to current file") }
            }
        } else {
            displayFiles.distinct().forEach { file ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (file == activeFile) Color(0xFF173D5C) else IdeSurfaceRaised, RoundedCornerShape(10.dp))
                        .clickable { onOpenFile(file) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (file.endsWith(".cpp")) "C++" else if (file.endsWith(".h")) "H" else "C",
                        color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file, color = if (file == activeFile) IdeAccent else Color(0xFFE8EDF5), fontSize = 13.sp)
                        Text(if (file == activeFile) "Currently active" else "Sample project file", color = IdeMuted, fontSize = 10.sp)
                    }
                    Text("›", color = IdeMuted, fontSize = 20.sp)
                }
            }
            Text("Recent history is kept in memory for this app session only.", color = IdeMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(10.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = IdeMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color(0xFFE8EDF5), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WelcomeScreen(onNext: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(IdeBackground)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .background(IdeSurface, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "</>",
                    color = IdeAccent,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "C/C++ IDE",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF2F6FC)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Next  →", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(selected: String, onSelect: (String) -> Unit, onContinue: () -> Unit, onBack: () -> Unit) {
    Scaffold(
        containerColor = IdeBackground,
        topBar = {
            TopAppBar(
                title = { Text("Environment Setup", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Text(
                        "‹",
                        Modifier.clickable(onClick = onBack).padding(horizontal = 18.dp),
                        fontSize = 32.sp,
                        color = IdeAccent
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = IdeBackground)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Choose your Linux environment",
                color = Color(0xFFF2F6FC),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "C/C++ tools will run inside this environment.",
                color = IdeMuted,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            SetupChoiceCard(
                title = "Download Ubuntu",
                subtitle = "Download and configure automatically",
                icon = "↓",
                selected = selected == "Download Ubuntu from Internet",
                onClick = { onSelect("Download Ubuntu from Internet") }
            )
            SetupChoiceCard(
                title = "Import a root filesystem",
                subtitle = "Choose a Linux rootfs archive from storage",
                icon = "▣",
                selected = selected == "Use Downloaded Rootfs from Storage",
                onClick = { onSelect("Use Downloaded Rootfs from Storage") }
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Setup actions are not connected in this UI prototype yet.",
                color = IdeMuted,
                fontSize = 11.sp
            )
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Continue  →", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SetupChoiceCard(title: String, subtitle: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF1D3047) else IdeSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) IdeAccent else IdeSurfaceRaised),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = IdeAccent, fontSize = 25.sp, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Color(0xFFF0F4FA))
                Text(subtitle, color = IdeMuted, fontSize = 12.sp)
            }
            Text(if (selected) "●" else "○", color = IdeAccent, fontSize = 20.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListScreen(
    projects: List<IdeProject>,
    onOpen: (IdeProject) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = IdeBackground,
        topBar = {
            TopAppBar(
                title = { Column { Text("Projects", fontWeight = FontWeight.Bold); Text("Your coding workspace", color = IdeMuted, fontSize = 12.sp) } },
                actions = { Text("⚙", Modifier.clickable(onClick = onSettings).padding(horizontal = 18.dp), fontSize = 22.sp, color = IdeMuted) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = IdeBackground)
            )
        },
        floatingActionButton = {
            Button(onClick = onNew) { Text("+  New Project") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search projects") },
                leadingIcon = { IdeIcon("search", IdeAccent, 18.dp) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF172536)),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF29435D))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Color(0xFF263D55), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Text("C/C++", color = IdeAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Start coding", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text("Create or open a C/C++ workspace", color = IdeMuted, fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onNew, modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp)) {
                            IdeIcon("add", Color.White, 16.dp)
                            Spacer(Modifier.width(5.dp))
                            Text("New project", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f), shape = RoundedCornerShape(6.dp)) {
                            IdeIcon("settings", IdeAccent, 16.dp)
                            Spacer(Modifier.width(5.dp))
                            Text("Setup", fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT", fontSize = 12.sp, letterSpacing = 1.sp, color = IdeMuted, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${projects.size} projects", color = IdeMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            val visible = projects.filter { it.name.contains(query, ignoreCase = true) }
            if (visible.isEmpty()) {
                EmptyProjects(onNew = onNew)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(visible, key = { it.name }) { project ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(project) },
                            colors = CardDefaults.cardColors(containerColor = IdeSurface),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(44.dp).background(IdeSurfaceRaised, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Text(if (project.language == "C++20") "C++" else "C", color = IdeAccent, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(project.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                        Text(project.language, color = IdeAccent, fontSize = 12.sp)
                                    }
                                    Text("›", color = IdeMuted, fontSize = 28.sp)
                                }
                                Text(project.path, color = IdeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Updated ${project.updated}", color = IdeMuted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProjects(onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("▤", color = IdeAccent, fontSize = 42.sp)
        Text("No projects found", fontWeight = FontWeight.SemiBold)
        Text("Create a project to start coding.", color = IdeMuted)
        Button(onClick = onNew) { Text("Create your first project") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceScreen(
    projectName: String,
    activeFile: String,
    code: String,
    openFiles: List<String>,
    dirtyFiles: List<String>,
    onCodeChange: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onCloseFile: (String) -> Unit,
    editorController: SoraEditorController,
    editorFontSize: Float,
    editorWordWrap: Boolean,
    onMenu: () -> Unit,
    onExplorer: () -> Unit,
    onFile: (String) -> Unit,
    onAction: (String) -> Unit,
    selectedPanel: ToolPanel,
    onPanel: (ToolPanel) -> Unit
) {
    Scaffold(
        containerColor = IdeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        FileTypeIcon(activeFile, 25.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(activeFile + if (dirtyFiles.contains(activeFile)) "  ●" else "", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(projectName, fontSize = 11.sp, color = IdeMuted, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenu) { IdeIcon("menu", IdeAccent, 23.dp) }
                },
                actions = {
                    IconButton(onClick = onExplorer) { IdeIcon("folder", IdeAccent, 20.dp) }
                    IconButton(onClick = { onAction("Undo") }) { IdeIcon("undo", IdeMuted, 21.dp) }
                    IconButton(onClick = { onAction("Redo") }) { IdeIcon("redo", IdeMuted, 21.dp) }
                    IconButton(onClick = { onAction("Save") }) { IdeIcon("save", IdeAccent, 19.dp) }
                    IconButton(onClick = { onAction("Build") }) { IdeIcon("build", IdeGreen, 19.dp) }
                    IconButton(onClick = { onAction("Run") }) { IdeIcon("run", IdeGreen, 19.dp) }
                    IconButton(onClick = { onAction("Find") }) { IdeIcon("search", IdeMuted, 19.dp) }
                    var showOverflow by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOverflow = true }) { IdeIcon("more", IdeMuted, 20.dp) }
                        androidx.compose.material3.DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            listOf("Command Palette", "Find", "Find in Project", "Save All", "File Explorer", "Terminal", "Build", "Problems", "Output", "Settings", "Sync Project", "Cancel Build").forEach { action ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(action) },
                                    leadingIcon = { IdeIcon(when (action) { "Find", "Find in Project", "Command Palette" -> "search"; "File Explorer" -> "folder"; "Terminal" -> "terminal"; "Build", "Cancel Build" -> "build"; "Problems" -> "problems"; "Output", "Sync Project" -> "output"; "Settings" -> "settings"; else -> "save" }, IdeAccent, 18.dp) },
                                    onClick = { showOverflow = false; when (action) { "File Explorer" -> onExplorer(); "Terminal", "Build", "Problems", "Output" -> onAction(action); else -> onAction(action) } }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = IdeSurface)
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(IdeSurface)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 6.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        Triple("PROBLEMS", ToolPanel.Problems, "problems"),
                        Triple("OUTPUT", ToolPanel.Output, "output"),
                        Triple("DEBUG CONSOLE", ToolPanel.Build, "build"),
                        Triple("TERMINAL", ToolPanel.Terminal, "terminal")
                    ).forEach { (label, panel, icon) ->
                        val selected = selectedPanel == panel
                        Column(
                            Modifier.clickable { onPanel(panel) }.padding(end = 18.dp, top = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                IdeIcon(icon, if (selected) IdeAccent else IdeMuted, 15.dp)
                                Text(label, color = if (selected) Color(0xFFE8EDF5) else IdeMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            }
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.width(28.dp).height(2.dp).background(if (selected) IdeAccent else Color.Transparent))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("⌃", Modifier.clickable { onPanel(selectedPanel) }.padding(horizontal = 10.dp, vertical = 6.dp), color = IdeMuted, fontSize = 16.sp)
                }
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF007ACC)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⌘", color = Color.White, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("main", color = Color.White, fontSize = 10.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("✓ UI Prototype", color = Color.White, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (activeFile.endsWith(".cpp")) "C++  •  UTF-8  •  Ln 1, Col 1" else if (activeFile.endsWith(".c")) "C  •  UTF-8  •  Ln 1, Col 1" else "UTF-8  •  Ln 1, Col 1", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(IdeBackground)
        ) {
            // Compact AndroidIDE-style open-file strip: never let a tab consume the full row width.
            Row(
                Modifier.fillMaxWidth().height(46.dp).background(Color(0xFF151A22)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    openFiles.forEach { file ->
                        FileTab(
                            name = file,
                            selected = file == activeFile,
                            dirty = dirtyFiles.contains(file),
                            onClick = { onSelectFile(file) },
                            onClose = { onCloseFile(file) }
                        )
                    }
                }
                Text(
                    "＋",
                    Modifier.clickable { onFile(if (activeFile == "main.c") "main.cpp" else if (activeFile == "main.cpp") "utils.h" else "main.c") }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = IdeAccent, fontSize = 20.sp
                )
            }
            Row(
                Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF10151C)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("workspace", color = IdeMuted, fontSize = 11.sp)
                Text("  ›  $projectName  ›  ", color = IdeMuted, fontSize = 11.sp)
                FileTypeIcon(activeFile, 15.dp)
                Text(" $activeFile", color = Color(0xFFCAD4E0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text("⋯", color = IdeMuted, fontSize = 20.sp)
            }
            // The editor owns all remaining vertical space, like AndroidIDE's workspace.
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0F1218))) {
                SoraCodeEditor(
                    code = code,
                    onCodeChange = onCodeChange,
                    modifier = Modifier.fillMaxSize(),
                    controller = editorController,
                    fontSize = editorFontSize,
                    wordWrap = editorWordWrap
                )
            }
        }
    }
}

@Composable
private fun FileExplorerSheet(
    projectName: String,
    activeFile: String,
    onOpenFile: (String) -> Unit,
    onClose: () -> Unit,
    sidebarMode: Boolean = false
) {
    var query by rememberSaveable(projectName) { mutableStateOf("") }
    var srcExpanded by rememberSaveable(projectName) { mutableStateOf(true) }
    var includeExpanded by rememberSaveable(projectName) { mutableStateOf(true) }
    val sourceFiles = remember(projectName) {
        mutableStateListOf(
            Triple("main.c", "C source file", "src"),
            Triple("main.cpp", "C++ source file", "src"),
            Triple("utils.h", "C/C++ header", "include")
        )
    }
    var fileDialog by rememberSaveable(projectName) { mutableStateOf(false) }
    var folderDialog by rememberSaveable(projectName) { mutableStateOf(false) }
    var newEntryName by rememberSaveable(projectName) { mutableStateOf("") }
    var entryMessage by rememberSaveable(projectName) { mutableStateOf<String?>(null) }
    val visibleFiles = sourceFiles.filter { it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = if (sidebarMode) 10.dp else 18.dp).padding(bottom = if (sidebarMode) 8.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(if (sidebarMode) 6.dp else 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("EXPLORER", fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCAD4E0))
                Text(projectName.uppercase(), color = IdeMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { srcExpanded = true; includeExpanded = true; query = "" }, modifier = Modifier.size(32.dp)) {
                IdeIcon("refresh", IdeMuted, 16.dp)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                IdeIcon("close", IdeMuted, 16.dp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { newEntryName = ""; fileDialog = true }, modifier = Modifier.weight(1f)) {
                IdeIcon("add", IdeAccent, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("New file", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { newEntryName = ""; folderDialog = true }, modifier = Modifier.weight(1f)) {
                IdeIcon("folder", IdeAccent, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("New folder", fontSize = 12.sp)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Find a file") },
            leadingIcon = { IdeIcon("search", IdeAccent, 20.dp) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { IdeIcon("close", IdeMuted, 15.dp) } }
        )
        Row(
            Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(4.dp)).padding(horizontal = 9.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IdeIcon("chevronDown", IdeMuted, 14.dp, Modifier.padding(end = 4.dp))
            IdeIcon("folder", Color(0xFFE4BE79), 18.dp, Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(projectName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                Text("${visibleFiles.size} files", color = IdeMuted, fontSize = 10.sp)
            }
            Text("⋯", color = IdeMuted, fontSize = 19.sp)
        }
        if (query.isBlank() || visibleFiles.any { it.third == "src" }) {
            Row(Modifier.fillMaxWidth().clickable { srcExpanded = !srcExpanded }.padding(start = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IdeIcon(if (srcExpanded || query.isNotBlank()) "chevronDown" else "chevronRight", IdeAccent, 15.dp, Modifier.width(24.dp))
                Text("src", color = Color(0xFFE8EDF5), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("2 files", color = IdeMuted, fontSize = 10.sp)
            }
            if (srcExpanded || query.isNotBlank()) visibleFiles.filter { it.third == "src" }.forEach { file ->
                ExplorerFileRow(file.first, file.second, activeFile == file.first, 28.dp) { onOpenFile(file.first) }
            }
        }
        if (query.isBlank() || visibleFiles.any { it.third == "include" }) {
            Row(Modifier.fillMaxWidth().clickable { includeExpanded = !includeExpanded }.padding(start = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IdeIcon(if (includeExpanded || query.isNotBlank()) "chevronDown" else "chevronRight", IdeAccent, 15.dp, Modifier.width(24.dp))
                Text("include", color = Color(0xFFE8EDF5), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("1 file", color = IdeMuted, fontSize = 10.sp)
            }
            if (includeExpanded || query.isNotBlank()) visibleFiles.filter { it.third == "include" }.forEach { file ->
                ExplorerFileRow(file.first, file.second, activeFile == file.first, 28.dp) { onOpenFile(file.first) }
            }
        }
        if (fileDialog || folderDialog) {
            val creatingFolder = folderDialog
            AlertDialog(
                onDismissRequest = { fileDialog = false; folderDialog = false },
                containerColor = IdeSurface,
                title = { Text(if (creatingFolder) "New folder" else "New file", color = Color(0xFFE8EDF5)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newEntryName,
                            onValueChange = { newEntryName = it },
                            singleLine = true,
                            label = { Text(if (creatingFolder) "Folder name" else "File name") },
                            placeholder = { Text(if (creatingFolder) "e.g. tests" else "e.g. helper.c") }
                        )
                        Text(if (creatingFolder) "Folders are preview-only in this UI stage." else "New files are added to the sample project tree.", color = IdeMuted, fontSize = 11.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = newEntryName.trim().substringAfterLast('/')
                        if (name.isNotBlank() && name !in sourceFiles.map { it.first }) {
                            if (creatingFolder) entryMessage = "Folder '$name' previewed; filesystem is not connected."
                            else {
                                val folder = if (name.endsWith(".h") || name.endsWith(".hpp")) "include" else "src"
                                val description = when {
                                    name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx") -> "C++ source file"
                                    name.endsWith(".h") || name.endsWith(".hpp") -> "C/C++ header"
                                    else -> "C source file"
                                }
                                sourceFiles.add(Triple(name, description, folder))
                                entryMessage = "Created '$name' in the preview tree."
                            }
                        } else if (name.isBlank()) entryMessage = "Enter a name first."
                        else entryMessage = "'$name' already exists."
                        fileDialog = false
                        folderDialog = false
                    }) { Text("Create", color = IdeAccent) }
                },
                dismissButton = { TextButton(onClick = { fileDialog = false; folderDialog = false }) { Text("Cancel", color = IdeMuted) } }
            )
        }
        entryMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { entryMessage = null },
                containerColor = IdeSurface,
                title = { Text("Explorer", color = Color(0xFFE8EDF5)) },
                text = { Text(message, color = IdeMuted) },
                confirmButton = { TextButton(onClick = { entryMessage = null }) { Text("OK", color = IdeAccent) } }
            )
        }
        if (visibleFiles.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No matching files", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold)
                Text("Try a different file name.", color = IdeMuted, fontSize = 12.sp)
            }
        }
        Divider(color = IdeSurfaceRaised)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sample project tree", color = IdeMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("Runtime not connected", color = Color(0xFFE5B978), fontSize = 10.sp)
        }
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
        modifier = modifier.size(iconSize).background(color.copy(alpha = 0.13f), RoundedCornerShape(4.dp)),
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
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = indent)
            .background(if (selected) Color(0xFF1D3047) else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileTypeIcon(name, 20.dp, Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (selected) IdeAccent else Color(0xFFE8EDF5), fontSize = 13.sp)
            Text(description, color = IdeMuted, fontSize = 10.sp)
        }
        if (selected) Text("●", color = IdeAccent, fontSize = 10.sp)
    }
}

@Composable
private fun FileTab(name: String, selected: Boolean, dirty: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    val fileAccent = when {
        name.endsWith(".cpp") || name.endsWith(".hpp") -> Color(0xFF9CBEFF)
        name.endsWith(".h") -> Color(0xFFE5B978)
        else -> Color(0xFF86D6B5)
    }
    Column(
        Modifier
            .widthIn(min = 72.dp, max = 190.dp)
            .clickable(onClick = onClick)
            .background(if (selected) IdeSurface else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FileTypeIcon(name, 16.dp)
            Text(name, color = if (selected) Color(0xFFE8EDF5) else IdeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (dirty) Text("●", color = Color(0xFFE5B978), fontSize = 9.sp)
            IdeIcon("close", IdeMuted, 13.dp, Modifier.clickable(onClick = onClose).padding(start = 4.dp))
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) IdeAccent else Color.Transparent))
    }
}

@Composable
private fun BottomToolButton(label: String, icon: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .background(if (selected) Color(0xFF173D5C) else Color.Transparent, RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        IdeIcon(icon, if (selected) IdeAccent else IdeMuted, 18.dp)
        Text(label, color = if (selected) Color(0xFFE8EDF5) else IdeMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box(Modifier.width(18.dp).height(2.dp).background(if (selected) IdeAccent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun ToolPanelContent(selected: ToolPanel, onSelect: (ToolPanel) -> Unit, onClose: () -> Unit) {
    val title = when (selected) {
        ToolPanel.Terminal -> "Terminal"
        ToolPanel.Build -> "Build"
        ToolPanel.Problems -> "Problems"
        ToolPanel.Output -> "Output"
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("WORKSPACE TOOLS", color = IdeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("Done", Modifier.clickable(onClick = onClose).padding(8.dp), color = IdeAccent)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            ToolPanel.entries.forEach { panel ->
                val label = when (panel) {
                    ToolPanel.Terminal -> "Terminal"
                    ToolPanel.Build -> "Build"
                    ToolPanel.Problems -> "Problems"
                    ToolPanel.Output -> "Output"
                }
                if (panel == selected) Button(onClick = { onSelect(panel) }) { Text(label, fontSize = 12.sp) }
                else OutlinedButton(onClick = { onSelect(panel) }) { Text(label, fontSize = 12.sp) }
            }
        }
        when (selected) {
            ToolPanel.Terminal -> {
                ToolStatusCard("TERMINAL SESSION", "Not connected", "The shell runtime has not been started.", "›  Waiting for runtime integration", IdeAccent)
                Text("Terminal commands will be available after the Termux runtime bridge is connected.", color = IdeMuted, fontSize = 11.sp)
            }
            ToolPanel.Build -> {
                ToolStatusCard("BUILD STATUS", "Ready to configure", "No build has been run in this UI preview.", "Compiler: Clang / LLVM · Not connected", IdeAccent)
                Text("Build steps", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                PanelInfoRow("1", "Resolve project configuration", "Waiting for build integration")
                PanelInfoRow("2", "Compile C/C++ sources", "Compiler not connected")
                PanelInfoRow("3", "Link executable", "Not started")
            }
            ToolPanel.Problems -> {
                ToolStatusCard("DIAGNOSTICS", "No diagnostics", "No compiler or clangd results are available yet.", "0 errors  ·  0 warnings  ·  UI preview", IdeGreen)
                Text("Problem list will populate when the compiler and language server are connected.", color = IdeMuted, fontSize = 11.sp)
            }
            ToolPanel.Output -> {
                ToolStatusCard("PROGRAM OUTPUT", "No output yet", "Run output will appear here after execution support is connected.", "Execution target: Linux environment", IdeAccent)
                Box(
                    Modifier.fillMaxWidth().height(76.dp).background(Color(0xFF0B0F14), RoundedCornerShape(10.dp)).padding(12.dp)
                ) {
                    Text("Waiting for program execution…", color = IdeMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        }
        Text("Preview only · runtime/compiler integration is not connected", color = Color(0xFFE5B978), fontSize = 10.sp)
    }
}

@Composable
private fun ToolStatusCard(
    eyebrow: String,
    status: String,
    description: String,
    detail: String,
    accent: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141C)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(eyebrow, color = IdeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(9.dp).background(accent, RoundedCornerShape(50)))
                Text(status, color = Color(0xFFE8EDF5), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(description, color = IdeMuted, fontSize = 12.sp)
            Divider(color = IdeSurfaceRaised)
            Text(detail, color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PanelInfoRow(number: String, title: String, status: String) {
    Row(
        Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(9.dp)).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(26.dp).background(IdeSurface, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
            Text(number, color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color(0xFFE8EDF5), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(status, color = IdeMuted, fontSize = 10.sp)
        }
    }
}


@Composable
private fun IdeIcon(name: String, tint: Color, iconSize: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.085f).coerceAtLeast(1.4f)
        fun line(a: Float, b: Float, c: Float, d: Float) =
            drawLine(tint, Offset(w * a, h * b), Offset(w * c, h * d), strokeWidth = stroke, cap = StrokeCap.Round)
        fun rect(a: Float, b: Float, c: Float, d: Float) =
            drawRect(tint, Offset(w * a, h * b), Size(w * (c - a), h * (d - b)), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        fun circle(a: Float, b: Float, r: Float) =
            drawCircle(tint, r * w, Offset(w * a, h * b), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        when (name) {
            "menu" -> { line(.15f,.25f,.85f,.25f); line(.15f,.5f,.85f,.5f); line(.15f,.75f,.85f,.75f) }
            "folder" -> { line(.1f,.3f,.42f,.3f); line(.42f,.3f,.53f,.42f); line(.53f,.42f,.9f,.42f); line(.1f,.3f,.1f,.8f); line(.1f,.8f,.9f,.8f); line(.9f,.42f,.9f,.8f) }
            "undo" -> { line(.2f,.35f,.48f,.12f); line(.2f,.35f,.48f,.58f); line(.22f,.35f,.58f,.35f); line(.58f,.35f,.8f,.55f); line(.8f,.55f,.8f,.8f) }
            "redo" -> { line(.8f,.35f,.52f,.12f); line(.8f,.35f,.52f,.58f); line(.78f,.35f,.42f,.35f); line(.42f,.35f,.2f,.55f); line(.2f,.55f,.2f,.8f) }
            "save" -> { rect(.15f,.1f,.85f,.9f); rect(.28f,.1f,.68f,.38f); rect(.28f,.58f,.72f,.9f) }
            "build" -> { line(.2f,.8f,.8f,.2f); line(.18f,.2f,.42f,.2f); line(.18f,.2f,.18f,.45f); line(.58f,.8f,.82f,.8f); line(.82f,.55f,.82f,.8f) }
            "run" -> { line(.25f,.12f,.82f,.5f); line(.82f,.5f,.25f,.88f); line(.25f,.88f,.25f,.12f) }
            "search" -> { circle(.43f,.42f,.27f); line(.63f,.63f,.9f,.9f) }
            "terminal" -> { rect(.08f,.15f,.92f,.84f); line(.22f,.35f,.4f,.5f); line(.4f,.5f,.22f,.65f); line(.5f,.68f,.75f,.68f) }
            "problems" -> { line(.5f,.1f,.9f,.85f); line(.9f,.85f,.1f,.85f); line(.1f,.85f,.5f,.1f); line(.5f,.35f,.5f,.58f); circle(.5f,.72f,.018f) }
            "output" -> { line(.15f,.25f,.85f,.25f); line(.15f,.5f,.85f,.5f); line(.15f,.75f,.65f,.75f) }
            "close" -> { line(.25f,.25f,.75f,.75f); line(.75f,.25f,.25f,.75f) }
            "add" -> { line(.5f,.15f,.5f,.85f); line(.15f,.5f,.85f,.5f) }
            "more" -> { circle(.5f,.2f,.025f); circle(.5f,.5f,.025f); circle(.5f,.8f,.025f) }
            "home" -> { line(.1f,.45f,.5f,.12f); line(.5f,.12f,.9f,.45f); line(.2f,.42f,.2f,.88f); line(.2f,.88f,.8f,.88f); line(.8f,.88f,.8f,.42f); rect(.42f,.62f,.58f,.88f) }
            "history" -> { circle(.5f,.5f,.36f); line(.5f,.25f,.5f,.5f); line(.5f,.5f,.7f,.62f); line(.12f,.22f,.12f,.43f); line(.12f,.22f,.32f,.22f) }
            "settings" -> { circle(.5f,.5f,.25f); circle(.5f,.5f,.08f); line(.5f,.06f,.5f,.2f); line(.5f,.8f,.5f,.94f); line(.06f,.5f,.2f,.5f); line(.8f,.5f,.94f,.5f); line(.19f,.19f,.29f,.29f); line(.71f,.71f,.81f,.81f); line(.19f,.81f,.29f,.71f); line(.71f,.29f,.81f,.19f) }
            "chevronDown" -> { line(.2f,.35f,.5f,.65f); line(.5f,.65f,.8f,.35f) }
            "chevronRight" -> { line(.35f,.2f,.65f,.5f); line(.65f,.5f,.35f,.8f) }
            "code", "cpp" -> { line(.32f,.25f,.12f,.5f); line(.12f,.5f,.32f,.75f); line(.68f,.25f,.88f,.5f); line(.88f,.5f,.68f,.75f); line(.58f,.15f,.42f,.85f) }
            "header" -> { rect(.2f,.1f,.8f,.9f); line(.35f,.38f,.65f,.38f); line(.35f,.55f,.65f,.55f); line(.35f,.72f,.55f,.72f) }
            else -> circle(.5f,.5f,.3f)
        }
    }
}

private fun sampleCode(file: String): String = when {
    file.endsWith(".cpp") -> "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, C++20!\\n\";\n    return 0;\n}"
    file.endsWith(".h") -> "#ifndef UTILS_H\n#define UTILS_H\n\nvoid greet(void);\n\n#endif"
    else -> "#include <stdio.h>\n\nint main(void) {\n    printf(\"Hello, world!\\n\");\n    return 0;\n}"
}
