package com.techwithkaushik.cppide

import android.os.Bundle
import com.techwithkaushik.cppide.feature.editor.SoraCodeEditor
import com.techwithkaushik.cppide.feature.editor.SoraEditorController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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

private val IdeBackground = Color(0xFF101319)
private val IdeSurface = Color(0xFF1A2029)
private val IdeSurfaceRaised = Color(0xFF242C38)
private val IdeAccent = Color(0xFF83B7FF)
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
    var newProjectDialog by rememberSaveable { mutableStateOf(false) }
    var newProjectName by rememberSaveable { mutableStateOf("") }
    var showToolPanel by rememberSaveable { mutableStateOf(false) }
    var showExplorer by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
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
                onOpen = { project -> selectedProject = project.name; activeFile = if (project.language == "C++20") "main.cpp" else "main.c"; screen = IdeScreen.Workspace },
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
                            Text(
                                when (item) {
                                    "File Explorer" -> "▤"
                                    "Terminal" -> ">_"
                                    "Build / Problems" -> "✓"
                                    "Project Overview" -> "⌂"
                                    "Recent Files" -> "◷"
                                    "Settings" -> "⚙"
                                    else -> "⬇"
                                },
                                color = if (active) IdeAccent else IdeMuted,
                                modifier = Modifier.width(32.dp)
                            )
                            Text(item, color = if (active) IdeAccent else Color(0xFFE8EDF5))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("UI prototype • runtime not connected", color = IdeMuted, fontSize = 11.sp, modifier = Modifier.padding(18.dp))
                }
            }
        ) {
            WorkspaceScreen(
                projectName = selectedProject,
                activeFile = activeFile,
                code = code,
                onCodeChange = { code = it },
                editorController = editorController,
                editorFontSize = editorFontSize,
                editorWordWrap = editorWordWrap,
                onMenu = { scope.launch { drawerState.open() } },
                onExplorer = { showExplorer = true },
                onFile = { file -> activeFile = file; code = sampleCode(file) },
                onAction = { action ->
                    when (action) {
                        "Terminal" -> { selectedPanel = ToolPanel.Terminal; showToolPanel = true }
                        "Build" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                        "Run" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                        "Problems" -> { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                        "Save" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                        "Undo" -> editorController.undo()
                        "Redo" -> editorController.redo()
                    }
                },
                selectedPanel = selectedPanel,
                onPanel = { selectedPanel = it; showToolPanel = true }
            )
        }
        if (showSettings) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSettings = false },
                containerColor = IdeSurface,
                title = { Text("Editor Settings", color = Color(0xFFE8EDF5)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Font size: ${editorFontSize.toInt()} sp", color = IdeMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editorFontSize = (editorFontSize - 1f).coerceAtLeast(10f) }) { Text("A−") }
                            OutlinedButton(onClick = { editorFontSize = (editorFontSize + 1f).coerceAtMost(24f) }) { Text("A+") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Word wrap", color = Color(0xFFE8EDF5))
                                Text("Wrap long source lines", color = IdeMuted, fontSize = 12.sp)
                            }
                            androidx.compose.material3.Switch(checked = editorWordWrap, onCheckedChange = { editorWordWrap = it })
                        }
                        Text("Language: C / C++ • Editor: Sora", color = IdeMuted, fontSize = 12.sp)
                    }
                },
                confirmButton = { Button(onClick = { showSettings = false }) { Text("Done") } }
            )
        }
        if (showExplorer) {
            ModalBottomSheet(
                onDismissRequest = { showExplorer = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = IdeSurface,
                contentWindowInsets = { WindowInsets.navigationBars }
            ) {
                FileExplorerSheet(
                    projectName = selectedProject,
                    activeFile = activeFile,
                    onOpenFile = { file ->
                        activeFile = file
                        code = sampleCode(file)
                        showExplorer = false
                    },
                    onClose = { showExplorer = false }
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
                singleLine = true
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent projects", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
    onCodeChange: (String) -> Unit,
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
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(activeFile, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(projectName, fontSize = 11.sp, color = IdeMuted, maxLines = 1)
                    }
                },
                navigationIcon = {
                    Text("☰", Modifier.clickable(onClick = onMenu).padding(horizontal = 14.dp), color = IdeAccent, fontSize = 23.sp)
                },
                actions = {
                    Text("▤", Modifier.clickable(onClick = onExplorer).padding(horizontal = 6.dp), fontSize = 20.sp, color = IdeAccent)
                    Text("↶", Modifier.clickable { onAction("Undo") }.padding(horizontal = 6.dp), fontSize = 22.sp, color = IdeMuted)
                    Text("↷", Modifier.clickable { onAction("Redo") }.padding(horizontal = 6.dp), fontSize = 22.sp, color = IdeMuted)
                    Text("▣", Modifier.clickable { onAction("Save") }.padding(horizontal = 6.dp), fontSize = 18.sp, color = IdeAccent)
                    Text("▶", Modifier.clickable { onAction("Build") }.padding(horizontal = 6.dp), fontSize = 17.sp, color = IdeGreen)
                    Text("▷", Modifier.clickable { onAction("Run") }.padding(start = 6.dp, end = 12.dp), fontSize = 19.sp, color = IdeGreen)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = IdeSurface)
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(IdeSurface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomToolButton("Terminal", ">_", selectedPanel == ToolPanel.Terminal) { onPanel(ToolPanel.Terminal) }
                    BottomToolButton("Build", "▤", selectedPanel == ToolPanel.Build) { onPanel(ToolPanel.Build) }
                    BottomToolButton("Problems", "!", selectedPanel == ToolPanel.Problems) { onPanel(ToolPanel.Problems) }
                    BottomToolButton("Output", "≋", selectedPanel == ToolPanel.Output) { onPanel(ToolPanel.Output) }
                }
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0C1016)).padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("● READY", color = IdeGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (activeFile.endsWith(".cpp")) "C++20 • UTF-8 • Ln 1, Col 1" else "C17 • UTF-8 • Ln 1, Col 1",
                        color = IdeMuted, fontSize = 10.sp
                    )
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
                FileTab(activeFile, selected = true) { onFile(activeFile) }
                Spacer(Modifier.weight(1f))
                Text(
                    "＋",
                    Modifier.clickable { onFile(if (activeFile == "main.c") "utils.h" else "main.c") }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    color = IdeAccent, fontSize = 20.sp
                )
            }
            Row(
                Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF10151C)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Projects", color = IdeMuted, fontSize = 11.sp)
                Text("  ›  $projectName  ›  $activeFile", color = IdeMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onClose: () -> Unit
) {
    var query by rememberSaveable(projectName) { mutableStateOf("") }
    var srcExpanded by rememberSaveable(projectName) { mutableStateOf(true) }
    var includeExpanded by rememberSaveable(projectName) { mutableStateOf(true) }
    val sourceFiles = listOf(
        Triple("main.c", "C source file", "src"),
        Triple("main.cpp", "C++ source file", "src"),
        Triple("utils.h", "C/C++ header", "include")
    )
    val visibleFiles = sourceFiles.filter { it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("File Explorer", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("PROJECT  /  $projectName", color = IdeMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Done", Modifier.clickable(onClick = onClose).padding(8.dp), color = IdeAccent)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Find a file") },
            leadingIcon = { Text("⌕", color = IdeAccent, fontSize = 20.sp) },
            trailingIcon = { if (query.isNotEmpty()) Text("×", Modifier.clickable { query = "" }.padding(8.dp), color = IdeMuted) }
        )
        Row(
            Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("▾", color = IdeAccent, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(projectName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("PROJECT ROOT  •  ${visibleFiles.size} matching files", color = IdeMuted, fontSize = 10.sp)
            }
            Text("C/C++", color = IdeGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (query.isBlank() || visibleFiles.any { it.third == "src" }) {
            Row(Modifier.fillMaxWidth().clickable { srcExpanded = !srcExpanded }.padding(start = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (srcExpanded || query.isNotBlank()) "▾" else "▸", color = IdeAccent, modifier = Modifier.width(24.dp))
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
                Text(if (includeExpanded || query.isNotBlank()) "▾" else "▸", color = IdeAccent, modifier = Modifier.width(24.dp))
                Text("include", color = Color(0xFFE8EDF5), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("1 file", color = IdeMuted, fontSize = 10.sp)
            }
            if (includeExpanded || query.isNotBlank()) visibleFiles.filter { it.third == "include" }.forEach { file ->
                ExplorerFileRow(file.first, file.second, activeFile == file.first, 28.dp) { onOpenFile(file.first) }
            }
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
        Text(if (name.endsWith(".h")) "◇" else "‹›", color = IdeAccent, fontSize = 16.sp, modifier = Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = if (selected) IdeAccent else Color(0xFFE8EDF5), fontSize = 13.sp)
            Text(description, color = IdeMuted, fontSize = 10.sp)
        }
        if (selected) Text("●", color = IdeAccent, fontSize = 10.sp)
    }
}

@Composable
private fun FileTab(name: String, selected: Boolean, onClick: () -> Unit) {
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
            Text(if (name.endsWith(".cpp") || name.endsWith(".hpp")) "C++" else "C", color = fileAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(name, color = if (selected) Color(0xFFE8EDF5) else IdeMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            .background(if (selected) Color(0xFF26384D) else Color.Transparent, RoundedCornerShape(11.dp))
            .padding(horizontal = 13.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(icon, color = if (selected) IdeAccent else IdeMuted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(label, color = if (selected) Color(0xFFE8EDF5) else IdeMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        Box(Modifier.width(18.dp).height(2.dp).background(if (selected) IdeAccent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun ToolPanelContent(selected: ToolPanel, onSelect: (ToolPanel) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (selected) {
                    ToolPanel.Terminal -> "Terminal"
                    ToolPanel.Build -> "Build"
                    ToolPanel.Problems -> "Problems"
                    ToolPanel.Output -> "Output"
                },
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text("Done", Modifier.clickable(onClick = onClose).padding(8.dp), color = IdeAccent)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolPanel.entries.forEach { panel ->
                val label = when (panel) {
                    ToolPanel.Terminal -> "Terminal"
                    ToolPanel.Build -> "Build"
                    ToolPanel.Problems -> "Problems"
                    ToolPanel.Output -> "Output"
                }
                if (panel == selected) Button(onClick = { onSelect(panel) }) { Text(label) }
                else OutlinedButton(onClick = { onSelect(panel) }) { Text(label) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF0B0F14), RoundedCornerShape(12.dp)).padding(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                when (selected) {
                    ToolPanel.Terminal -> {
                        Text("C/C++ IDE terminal", color = IdeGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Runtime is not connected yet.", color = Color(0xFFD9E4F4), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("This panel is UI-only; no shell process has been started.", color = IdeMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("›  Waiting for runtime integration", color = IdeAccent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    ToolPanel.Build -> {
                        Text("BUILD", color = IdeAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Build UI preview", color = Color(0xFFD9E4F4), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Compiler process is not connected yet.", color = IdeMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    ToolPanel.Problems -> {
                        Text("No diagnostics to display", color = IdeGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("Problem reporting will be connected with the compiler/LSP later.", color = IdeMuted, fontSize = 11.sp)
                    }
                    ToolPanel.Output -> {
                        Text("OUTPUT", color = IdeAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("No program output yet.", color = Color(0xFFD9E4F4), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun sampleCode(file: String): String = when {
    file.endsWith(".cpp") -> "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, C++20!\\n\";\n    return 0;\n}"
    file.endsWith(".h") -> "#ifndef UTILS_H\n#define UTILS_H\n\nvoid greet(void);\n\n#endif"
    else -> "#include <stdio.h>\n\nint main(void) {\n    printf(\"Hello, world!\\n\");\n    return 0;\n}"
}
