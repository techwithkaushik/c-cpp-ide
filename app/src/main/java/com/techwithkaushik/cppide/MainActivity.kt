package com.techwithkaushik.cppide

import com.techwithkaushik.cppide.feature.terminal.TermuxLinuxTerminalPanel
import com.techwithkaushik.cppide.feature.terminal.IdeMasterShellUi
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimeSmokeTest
import com.techwithkaushik.cppide.integration.linuxruntime.ProotDownloader
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import com.techwithkaushik.cppide.core.logging.DeveloperLog

import android.os.Bundle
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.techwithkaushik.cppide.feature.editor.SoraCodeEditor
import com.techwithkaushik.cppide.feature.editor.SoraEditorController
import com.techwithkaushik.cppide.feature.editor.ClangdProjectSession
import com.techwithkaushik.cppide.feature.editor.EditorLspStatus
import com.techwithkaushik.cppide.feature.editor.EditorProblem
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

private val IdeBackground = IdeDesignTokens.Color.Background
private val IdeSurface = IdeDesignTokens.Color.Surface
private val IdeSurfaceRaised = IdeDesignTokens.Color.SurfaceRaised
private val IdeAccent = IdeDesignTokens.Color.Accent
private val IdeMuted = IdeDesignTokens.Color.TextSecondary
private val IdeGreen = IdeDesignTokens.Color.Green

private enum class IdeScreen { Welcome, Projects, Workspace, Settings }
private enum class ToolPanel { Terminal, Build, Problems, Output }

private data class IdeProject(val name: String, val language: String, val path: String, val updated: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings = getSharedPreferences(IdeDesignTokens.Preference.FILE, MODE_PRIVATE)
            var uiFontScale by rememberSaveable {
                mutableStateOf(settings.getFloat(IdeDesignTokens.Preference.UI_FONT_SCALE, IdeDesignTokens.Metrics.DEFAULT_UI_FONT_SCALE).coerceIn(IdeDesignTokens.Metrics.MIN_UI_FONT_SCALE, IdeDesignTokens.Metrics.MAX_UI_FONT_SCALE))
            }
            val systemDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(systemDensity.density, uiFontScale)) {
                MaterialTheme(
                    colorScheme = androidx.compose.material3.darkColorScheme(
                        primary = IdeAccent,
                        onPrimary = IdeDesignTokens.Color.OnPrimary,
                        background = IdeBackground,
                        surface = IdeSurface,
                        onSurface = IdeDesignTokens.Color.TextPrimary,
                        onSurfaceVariant = IdeMuted,
                        secondary = IdeGreen
                    )
                ) {
                    Surface(Modifier.fillMaxSize(), color = IdeBackground) {
                        IdeUiPrototype(
                            uiFontScale = uiFontScale,
                            onUiFontScaleChange = {
                                uiFontScale = it.coerceIn(0.85f, 1.25f)
                                settings.edit().putFloat(IdeDesignTokens.Preference.UI_FONT_SCALE, uiFontScale).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdeUiPrototype(uiFontScale: Float, onUiFontScaleChange: (Float) -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val projectStorage = remember(appContext) { ProjectStorage(appContext) }
    var runtimeValidationComplete by remember { mutableStateOf(false) }

    // Validate the app-owned Ubuntu runtime once on every cold app launch.
    // The result is cached for this launch so users do not need to open Settings.
    LaunchedEffect(appContext) {
        val runtimeValidationStartedAt = System.nanoTime()
        DeveloperLog.event("Application", "runtime_validation_started", mapOf("sdk" to android.os.Build.VERSION.SDK_INT, "abi" to android.os.Build.SUPPORTED_ABIS.firstOrNull(), "nativeLibraryDir" to appContext.applicationInfo.nativeLibraryDir))
        val runtimePrefs = appContext.getSharedPreferences("linux_runtime_validation", android.content.Context.MODE_PRIVATE)
        val result = withContext(Dispatchers.IO) {
            val paths = LinuxRuntimePaths(appContext.filesDir, appContext.applicationInfo.nativeLibraryDir)
            // Materialize APK-bundled PRoot aliases before checking readiness. The
            // native executables live in nativeLibraryDir, not files/usr/bin.
            val prootCheck = runCatching {
                // Also validates ELF format and device ABI, not just file presence.
                ProotDownloader(paths).downloadAndInstall()
            }
            prootCheck.onSuccess { DeveloperLog.event("Application", "bundled_proot_validated", mapOf("bytes" to it.bytes, "release" to it.release)) }
                .onFailure { DeveloperLog.failure("Application", "bundled_proot_validation_failed", it, mapOf("proot" to paths.proot.absolutePath, "loader" to paths.prootLoader.absolutePath)) }
            DeveloperLog.event("Application", "rootfs_validation_result", mapOf("rootfsExists" to paths.hasRootfs(), "rootfsDiagnostic" to paths.rootfsDiagnostic()))
            when {
                !paths.hasRootfs() -> "Ubuntu rootfs is not installed or incomplete."
                prootCheck.isFailure -> "Bundled Android PRoot validation failed: ${prootCheck.exceptionOrNull()?.message ?: "unknown error"}"
                else -> "Ubuntu runtime binaries ready; shell smoke test deferred until terminal start."
            }
        }
        if (result == "Ubuntu runtime binaries ready; shell smoke test deferred until terminal start.") {
            DeveloperLog.info(appContext, "Ubuntu", "Startup runtime binary validation succeeded")
        } else {
            DeveloperLog.error(appContext, "Ubuntu", "Startup runtime validation failed: $result")
        }
        DeveloperLog.event("Application", "runtime_validation_finished", mapOf("result" to result, "durationMs" to (System.nanoTime() - runtimeValidationStartedAt) / 1_000_000))
        runtimePrefs.edit()
            .putBoolean("ready", result.startsWith("Ubuntu runtime binaries ready"))
            .putString("message", result)
            .putLong("checked_at", System.currentTimeMillis())
            .apply()
        runtimeValidationComplete = true
    }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val localView = LocalView.current
    val hostActivity = localView.context as? Activity
    SideEffect {
        hostActivity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, !fullScreen)
            val controller = WindowCompat.getInsetsController(window, localView)
            if (fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val onboardingPreferences = remember(appContext) { appContext.getSharedPreferences(IdeDesignTokens.Preference.FILE, android.content.Context.MODE_PRIVATE) }
    var screen by rememberSaveable { mutableStateOf(if (onboardingPreferences.getBoolean(IdeDesignTokens.Preference.WELCOME_COMPLETED, false)) IdeScreen.Projects else IdeScreen.Welcome) }
    var selectedProject by rememberSaveable { mutableStateOf("Welcome Workspace") }
    var activeFile by rememberSaveable { mutableStateOf("README.md") }
    var code by rememberSaveable { mutableStateOf("# Welcome Workspace\n\nOpen any file to edit it.\n") }
    val openFiles = remember { mutableStateListOf("README.md") }
    val dirtyFiles = remember { mutableStateListOf<String>() }
    // One source of truth for Explorer, tabs, search, and editor.
    val workspaceFiles = remember { mutableStateListOf("README.md") }
    val workspaceDirectories = remember { mutableStateListOf<String>() }
    val fileContents = remember {
        mutableStateMapOf("README.md" to code)
    }
    var newProjectDialog by rememberSaveable { mutableStateOf(false) }
    var newFileDialog by rememberSaveable { mutableStateOf(false) }
    var newFileName by rememberSaveable { mutableStateOf("") }
    var pendingCloseFile by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCloseError by rememberSaveable { mutableStateOf<String?>(null) }
    var newProjectName by rememberSaveable { mutableStateOf("") }
    var newProjectLanguage by rememberSaveable { mutableStateOf("C17") }
    var newProjectError by rememberSaveable { mutableStateOf<String?>(null) }
    var showToolPanel by rememberSaveable { mutableStateOf(false) }
    // Keep the editor usable on narrow phone screens; Explorer opens from the Activity Bar or drawer.
    var showExplorer by rememberSaveable { mutableStateOf(false) }
    var activeActivity by rememberSaveable { mutableStateOf("Explorer") }
    var showProjectOverview by rememberSaveable { mutableStateOf(false) }
    var showRecentFiles by rememberSaveable { mutableStateOf(false) }
    var recentFiles = remember { mutableStateListOf<String>() }
    fun openFile(file: String) {
        DeveloperLog.event(appContext, "Workspace", "file_open_requested", mapOf("project" to selectedProject, "file" to file, "cached" to fileContents.containsKey(file)))
        if (!openFiles.contains(file)) openFiles.add(file)
        activeFile = file
        code = fileContents[file] ?: sampleCode(file).also { fileContents[file] = it }
        recentFiles.remove(file)
        recentFiles.add(0, file)
        DeveloperLog.event(appContext, "Workspace", "file_opened", mapOf("project" to selectedProject, "file" to file, "characters" to code.length, "openTabCount" to openFiles.size))
    }
    fun closeFile(file: String) {
        DeveloperLog.event(appContext, "Workspace", "file_close_requested", mapOf("project" to selectedProject, "file" to file, "dirty" to dirtyFiles.contains(file)))
        if (dirtyFiles.contains(file)) { pendingCloseError = null; pendingCloseFile = file; return }
        openFiles.remove(file)
        if (openFiles.isEmpty()) openFiles.add("README.md")
        if (activeFile == file) {
            val next = openFiles.last()
            activeFile = next
            code = fileContents[next] ?: sampleCode(next).also { fileContents[next] = it }
        }
    }
    var settingsReturnScreen by rememberSaveable { mutableStateOf(IdeScreen.Projects) }
    var showSourceControl by rememberSaveable { mutableStateOf(false) }
    var showExtensions by rememberSaveable { mutableStateOf(false) }
    var extensionQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    var commandQuery by rememberSaveable { mutableStateOf("") }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchInProject by rememberSaveable { mutableStateOf(false) }
    var editorFontSize by rememberSaveable(appContext) { mutableStateOf(appContext.getSharedPreferences(IdeDesignTokens.Preference.FILE, 0).getFloat(IdeDesignTokens.Preference.EDITOR_FONT_SIZE, IdeDesignTokens.Metrics.DEFAULT_EDITOR_FONT_SIZE)) }
    var editorWordWrap by rememberSaveable(appContext) { mutableStateOf(appContext.getSharedPreferences(IdeDesignTokens.Preference.FILE, 0).getBoolean(IdeDesignTokens.Preference.EDITOR_WORD_WRAP, false)) }
    var editorTheme by rememberSaveable(appContext) { mutableStateOf(appContext.getSharedPreferences(IdeDesignTokens.Preference.FILE, 0).getString(IdeDesignTokens.Preference.EDITOR_THEME, "Dark+ (VS Code)") ?: "Dark+ (VS Code)") }
    var selectedPanel by rememberSaveable { mutableStateOf(ToolPanel.Terminal) }
    var drawerDestination by rememberSaveable { mutableStateOf("File Explorer") }
    var drawerSidebarOpen by rememberSaveable { mutableStateOf(false) }
    val projects = remember(projectStorage) {
        mutableStateListOf<IdeProject>().apply {
            projectStorage.listProjects().forEach { project ->
                add(IdeProject(project.name, project.language, project.path, "Saved"))
            }
        }
    }
    val editorController = remember { SoraEditorController() }
    val runtimePaths = remember(appContext) {
        LinuxRuntimePaths(appContext.filesDir, appContext.applicationInfo.nativeLibraryDir)
    }
    val workspaceDirectory = remember(selectedProject) {
        projectStorage.listProjects().firstOrNull { it.name == selectedProject }?.directory
    }
    val clangdGuestPath = remember(appContext, screen) {
        ToolPathPreferences(appContext).getExecutable("c-cpp", "clangd")
    }
    var clangdSession by remember { mutableStateOf<ClangdProjectSession?>(null) }
    LaunchedEffect(workspaceDirectory?.absolutePath, clangdGuestPath, screen, runtimeValidationComplete) {
        clangdSession = null
        if (runtimeValidationComplete && screen == IdeScreen.Workspace && workspaceDirectory != null) {
            DeveloperLog.event(appContext, "ClangdLsp", "workspace_session_creation_started", mapOf("projectPath" to workspaceDirectory.absolutePath, "configuredClangd" to clangdGuestPath, "runtimeReady" to runtimePaths.ready()))
            clangdSession = withContext(Dispatchers.IO) {
                ClangdProjectSession(workspaceDirectory, runtimePaths, clangdGuestPath)
            }
            DeveloperLog.event(appContext, "ClangdLsp", "workspace_session_creation_finished", mapOf("projectPath" to workspaceDirectory.absolutePath, "status" to clangdSession?.status?.value?.label, "detail" to clangdSession?.status?.value?.detail, "error" to clangdSession?.status?.value?.error))
        } else {
            DeveloperLog.debug(appContext, "ClangdLsp", "workspace_session_creation_skipped; screen=$screen; runtimeValidationComplete=$runtimeValidationComplete; workspaceExists=${workspaceDirectory != null}")
        }
    }
    // Keep cleanup tied to the workspace/screen lifecycle, not to the session
    // state itself. Keying DisposableEffect(clangdSession) disposes the freshly
    // created session during the null -> session state transition.
    val currentClangdSession by rememberUpdatedState(clangdSession)
    DisposableEffect(workspaceDirectory?.absolutePath, screen) {
        onDispose {
            val sessionToDispose = currentClangdSession
            DeveloperLog.event(appContext, "ClangdLsp", "workspace_session_dispose_requested", mapOf("present" to (sessionToDispose != null)))
            runCatching { sessionToDispose?.close() }
                .onFailure { DeveloperLog.failure("ClangdLsp", "workspace_session_dispose_failed", it) }
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val currentFormatAction = rememberUpdatedState<() -> Unit>({
        scope.launch {
            val workspace = workspaceDirectory
            if (workspace == null || File(activeFile).extension.lowercase() !in
                setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx")
            ) {
                selectedPanel = ToolPanel.Problems
                showToolPanel = true
                return@launch
            }
            try {
                val formatted = withContext(Dispatchers.IO) {
                    UbuntuClangFormatRunner(runtimePaths, workspace).format(activeFile, code)
                }
                withContext(Dispatchers.IO) { projectStorage.writeFile(selectedProject, activeFile, formatted) }
                code = formatted; fileContents[activeFile] = formatted; dirtyFiles.remove(activeFile)
                clangdSession?.saveDocument(activeFile)
                DeveloperLog.info(appContext, "ClangFormat", "Formatted and saved $activeFile")
            } catch (error: Exception) {
                selectedPanel = ToolPanel.Problems; showToolPanel = true
                DeveloperLog.failure("ClangFormat", "format_document_failed", error, mapOf("file" to activeFile))
            }
        }
    })
    DisposableEffect(editorController) {
        editorController.setFormatAction { currentFormatAction.value.invoke() }
        onDispose { editorController.setFormatAction(null) }
    }

    // Linux runtime readiness is owned by the app's Ubuntu runtime integration.
    BackHandler(enabled = screen != IdeScreen.Welcome) {
        // Close transient UI first. In particular, Android Back must dismiss
        // the terminal bottom sheet even while the embedded TerminalView owns
        // keyboard focus; it must not be forwarded into the shell.
        when {
            showToolPanel -> showToolPanel = false
            drawerState.isOpen && drawerSidebarOpen -> drawerSidebarOpen = false
            drawerState.isOpen -> scope.launch { drawerState.close() }
            else -> when (screen) {
                IdeScreen.Workspace -> screen = IdeScreen.Projects
                IdeScreen.Projects -> if (!onboardingPreferences.getBoolean(IdeDesignTokens.Preference.WELCOME_COMPLETED, false)) screen = IdeScreen.Welcome
                IdeScreen.Settings -> screen = settingsReturnScreen
                IdeScreen.Welcome -> Unit
            }
        }
    }

    pendingCloseFile?.let { fileToClose ->
        AlertDialog(
            onDismissRequest = { pendingCloseFile = null; pendingCloseError = null },
            containerColor = IdeSurface,
            title = { Text("Unsaved changes", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                    Text("Save changes to $fileToClose before closing it?", color = IdeMuted)
                    pendingCloseError?.let { error -> Text(error, color = Color(0xFFFF8A80), fontSize = IdeDesignTokens.Typography.caption) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (fileToClose == activeFile) fileContents[fileToClose] = code
                    val contents = fileContents[fileToClose].orEmpty()
                    pendingCloseError = null
                    runCatching { projectStorage.writeFile(selectedProject, fileToClose, contents) }
                        .onSuccess {
                            dirtyFiles.remove(fileToClose)
                            pendingCloseFile = null
                            pendingCloseError = null
                            closeFile(fileToClose)
                        }
                        .onFailure { error ->
                            pendingCloseError = error.message?.takeIf { it.isNotBlank() }
                                ?.let { "Could not save file: $it" }
                                ?: "Could not save file. Check storage access and try again."
                        }
                }) { Text("Save", color = IdeAccent) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        dirtyFiles.remove(fileToClose)
                        pendingCloseFile = null
                        pendingCloseError = null
                        closeFile(fileToClose)
                    }) { Text("Discard", color = IdeMuted) }
                    TextButton(onClick = { pendingCloseFile = null; pendingCloseError = null }) { Text("Cancel", color = IdeAccent) }
                }
            }
        )
    }

    if (screen != IdeScreen.Workspace) {
        when (screen) {
            IdeScreen.Welcome -> WelcomeScreen(
                onNext = {
                    onboardingPreferences.edit().putBoolean(IdeDesignTokens.Preference.WELCOME_COMPLETED, true).apply()
                    screen = IdeScreen.Projects
                }
            )
            IdeScreen.Settings -> IdeSettingsScreen(
                editorFontSize = editorFontSize,
                onEditorFontSizeChange = { editorFontSize = it },
                editorWordWrap = editorWordWrap,
                onEditorWordWrapChange = { editorWordWrap = it },
                editorTheme = editorTheme,
                onEditorThemeChange = { editorTheme = it },
                projectName = selectedProject,
                uiFontScale = uiFontScale,
                onUiFontScaleChange = onUiFontScaleChange,
                onBack = { screen = settingsReturnScreen },
                onRuntimeReady = { screen = settingsReturnScreen }
            )
            IdeScreen.Projects -> ProjectListScreen(
                projects = projects,
                onOpen = { project ->
                    selectedProject = project.name
                    val diskFiles = runCatching { projectStorage.listFiles(project.name) }.getOrDefault(emptyList())
                    workspaceFiles.clear()
                    workspaceFiles.addAll(diskFiles)
                    workspaceDirectories.clear()
                    workspaceDirectories.addAll(projectStorage.listDirectories(project.name))
                    fileContents.clear()
                    diskFiles.forEach { file ->
                        runCatching { projectStorage.readFile(project.name, file) }
                            .onSuccess { fileContents[file] = it }
                    }
                    val entryFile = entryFileForLanguage(project.language)
                    if (workspaceFiles.isEmpty()) {
                        workspaceFiles.addAll(listOf(entryFile, "README.md").distinct())
                        fileContents[entryFile] = sampleCode(entryFile)
                        fileContents["README.md"] = sampleCode("README.md")
                    }
                    openFiles.clear()
                    dirtyFiles.clear()
                    openFile(if (entryFile in workspaceFiles) entryFile else workspaceFiles.first())
                    screen = IdeScreen.Workspace
                },
                onNew = { newProjectDialog = true },
                onSettings = { settingsReturnScreen = screen; screen = IdeScreen.Settings }
            )
            else -> Unit
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = IdeSurface,
                    drawerContentColor = Color(0xFFE8EDF5),
                    modifier = Modifier.width(if (drawerSidebarOpen) 296.dp else 56.dp),
                    drawerShape = androidx.compose.ui.graphics.RectangleShape
                ) {
                    Column(Modifier.fillMaxSize().background(IdeSurface)) {
                        if (drawerSidebarOpen) {
                            Row(
                                Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF151F29))
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { drawerSidebarOpen = false },
                                    modifier = Modifier.size(32.dp)
                                ) { IdeIcon("chevronRight", IdeMuted, 18.dp) }
                                Text(
                                    drawerDestination.uppercase(),
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFFE8EDF5),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { scope.launch { drawerState.close() } },
                                    modifier = Modifier.size(30.dp)
                                ) { IdeIcon("close", IdeMuted, 16.dp) }
                            }
                            HorizontalDivider(color = IdeSurfaceRaised, thickness = 1.dp)
                        } else {
                            Box(
                                Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF151F29)),
                                contentAlignment = Alignment.Center
                            ) {
                                IdeIcon("code", IdeAccent, 22.dp)
                            }
                            HorizontalDivider(color = IdeSurfaceRaised, thickness = 1.dp)
                        }
                        Row(Modifier.fillMaxSize()) {
                            Column(
                                Modifier.width(56.dp).fillMaxHeight()
                                    .background(Color(0xFF111820))
                                    .padding(top = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                listOf(
                                    Triple("File Explorer", "folder", "Explorer"),
                                    Triple("Search", "search", "Search"),
                                    Triple("Source Control", "git", "Source Control"),
                                    Triple("Run and Debug", "run", "Run and Debug"),
                                    Triple("Extensions", "extensions", "Extensions"),
                                    Triple("Settings", "settings", "Settings")
                                ).forEach { (destination, icon, label) ->
                                    val active = drawerDestination == destination && drawerSidebarOpen
                                    Box(
                                        Modifier.fillMaxWidth().height(48.dp)
                                            .clickable {
                                                drawerDestination = destination
                                                drawerSidebarOpen = true
                                            }
                                            .background(if (active) Color(0xFF263747) else Color.Transparent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (active) {
                                            Box(
                                                Modifier.align(Alignment.CenterStart).width(2.dp)
                                                    .height(30.dp).background(IdeAccent)
                                            )
                                        }
                                        androidx.compose.material3.IconButton(
                                            onClick = {
                                                drawerDestination = destination
                                                drawerSidebarOpen = true
                                            },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            IdeIcon(
                                                icon,
                                                if (active) Color(0xFFE8EDF5) else IdeMuted,
                                                21.dp
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Box(
                                    Modifier.fillMaxWidth().height(44.dp)
                                        .clickable { scope.launch { drawerState.close() } },
                                    contentAlignment = Alignment.Center
                                ) { IdeIcon("close", IdeMuted, 19.dp) }
                            }
                            if (drawerSidebarOpen) {
                                Column(
                                    Modifier.weight(1f).fillMaxHeight()
                                        .background(IdeSurface)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    when (drawerDestination) {
                                        "File Explorer" -> FileExplorerSheet(
                                            projectName = selectedProject,
                                            activeFile = activeFile,
                                            files = workspaceFiles.toList(),
                                            directories = workspaceDirectories.toList(),
                                            onOpenFile = { openFile(it); scope.launch { drawerState.close() } },
                                            onCreateFile = { file ->
                                                if (file !in workspaceFiles) workspaceFiles.add(file)
                                                if (file !in fileContents) fileContents[file] = sampleCode(file)
                                                openFile(file)
                                                scope.launch { drawerState.close() }
                                            },
                                            onCreateFolder = { folder ->
                                                withContext(Dispatchers.IO) { projectStorage.createDirectory(selectedProject, folder) }
                                                workspaceDirectories.clear()
                                                workspaceDirectories.addAll(withContext(Dispatchers.IO) { projectStorage.listDirectories(selectedProject) })
                                            },
                                            onClose = { drawerSidebarOpen = false },
                                            sidebarMode = true
                                        )
                                        "Search" -> {
                                            OutlinedTextField(
                                                value = searchQuery,
                                                onValueChange = { searchQuery = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                placeholder = { Text("Search files", fontSize = 12.sp) },
                                                leadingIcon = { IdeIcon("search", IdeAccent, 17.dp) }
                                            )
                                            Button(
                                                onClick = {
                                                    searchInProject = true
                                                    showSearch = true
                                                    scope.launch { drawerState.close() }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = androidx.compose.ui.graphics.RectangleShape
                                            ) { Text("Search project", fontSize = 12.sp) }
                                        }
                                        "Source Control" -> {
                                            Text(
                                                if (dirtyFiles.isEmpty()) "No changes" else "${dirtyFiles.size} changed file(s)",
                                                color = IdeMuted,
                                                fontSize = 12.sp
                                            )
                                            dirtyFiles.forEach {
                                                Text("M  $it", color = IdeGreen, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Button(
                                                onClick = {
                                                    var saveFailed = false
                                                    dirtyFiles.toList().forEach { file ->
                                                        val contents = if (file == activeFile) code else fileContents[file].orEmpty()
                                                        runCatching { projectStorage.writeFile(selectedProject, file, contents) }
                                                            .onSuccess { dirtyFiles.remove(file); clangdSession?.saveDocument(file) }
                                                            .onFailure { saveFailed = true }
                                                    }
                                                    if (saveFailed) {
                                                        selectedPanel = ToolPanel.Problems
                                                        showToolPanel = true
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = androidx.compose.ui.graphics.RectangleShape
                                            ) { Text("Save all", fontSize = 12.sp) }
                                        }
                                        "Run and Debug" -> {
                                            Button(
                                                onClick = { selectedPanel = ToolPanel.Build; showToolPanel = true; scope.launch { drawerState.close() } },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = androidx.compose.ui.graphics.RectangleShape
                                            ) { Text("Build", fontSize = 12.sp) }
                                            OutlinedButton(
                                                onClick = { selectedPanel = ToolPanel.Output; showToolPanel = true; scope.launch { drawerState.close() } },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = androidx.compose.ui.graphics.RectangleShape
                                            ) { Text("Run / Output", fontSize = 12.sp) }
                                        }
                                        "Extensions" -> Text("No extensions installed.", color = IdeMuted, fontSize = 12.sp)
                                        "Settings" -> Button(
                                            onClick = { settingsReturnScreen = screen; screen = IdeScreen.Settings; scope.launch { drawerState.close() } },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = androidx.compose.ui.graphics.RectangleShape
                                        ) { Text("Open settings", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ){
            Box(Modifier.fillMaxSize().background(IdeBackground)) {
                WorkspaceScreen(
                    projectName = selectedProject, activeFile = activeFile, code = code,
                    openFiles = openFiles, dirtyFiles = dirtyFiles,
                    onCodeChange = { updated -> code = updated; fileContents[activeFile] = updated; if (!dirtyFiles.contains(activeFile)) dirtyFiles.add(activeFile) },
                    onSelectFile = { openFile(it) }, onCloseFile = { closeFile(it) },
                    editorController = editorController, editorFontSize = editorFontSize, editorWordWrap = editorWordWrap, editorTheme = editorTheme,
                    lspSession = clangdSession,
                    onMenu = { scope.launch { drawerState.open() } },
                    onExplorer = { drawerDestination = "File Explorer"; scope.launch { drawerState.open() } },
                    onFile = { file -> openFile(file) },
                    onAction = { action ->
                        when (action) {
                            "Command Palette" -> { commandQuery = ""; showCommandPalette = true }
                            "New File" -> { newFileName = ""; newFileDialog = true }
                            "Terminal" -> { selectedPanel = ToolPanel.Terminal; showToolPanel = true }
                            "Build" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                            "Run", "Output" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                            "Problems" -> { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                            "Save" -> runCatching { projectStorage.writeFile(selectedProject, activeFile, code) }.onSuccess { dirtyFiles.remove(activeFile); fileContents[activeFile] = code; clangdSession?.saveDocument(activeFile) }.onFailure { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                            "Format Document" -> editorController.format()
                            "Undo" -> editorController.undo()
                            "Redo" -> editorController.redo()
                            "Select All" -> editorController.selectAll()
                            "Copy" -> editorController.copy()
                            "Cut" -> editorController.cut()
                            "Paste" -> editorController.paste()
                            "Toggle Word Wrap" -> editorWordWrap = !editorWordWrap
                            "Find" -> { searchInProject = false; searchQuery = ""; showSearch = true }
                            "Find in Project" -> { searchInProject = true; searchQuery = ""; showSearch = true }
                            "Save All" -> {
                                fileContents[activeFile] = code
                                dirtyFiles.toList().forEach { file -> runCatching { projectStorage.writeFile(selectedProject, file, fileContents[file].orEmpty()) }.onSuccess { dirtyFiles.remove(file); clangdSession?.saveDocument(file) }.onFailure { selectedPanel = ToolPanel.Problems; showToolPanel = true } }
                            }
                            "Settings" -> { settingsReturnScreen = screen; screen = IdeScreen.Settings }
                            "File Explorer" -> { drawerDestination = "File Explorer"; scope.launch { drawerState.open() } }
                            "Sync Project" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                            "Cancel Build" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                        }
                    },
                    selectedPanel = selectedPanel, showToolPanel = showToolPanel, fullScreen = fullScreen,
                    onToggleFullScreen = { fullScreen = !fullScreen },
                    onCloseToolPanel = { showToolPanel = false },
                    onPanel = { selectedPanel = it; showToolPanel = true },
                    onOpenSettings = { settingsReturnScreen = screen; screen = IdeScreen.Settings; showToolPanel = false }
                )
            }
        }
        if (showCommandPalette) {
            val commands = listOf(
                "New File", "Find", "Find in Project", "Format Document", "Select All", "Copy", "Cut", "Paste",
                "Undo", "Redo", "Toggle Word Wrap", "Save", "Save All",
                "Build", "Run", "Terminal", "Problems", "Output",
                "File Explorer", "Settings", "Sync Project"
            )
            val matches = commands.filter { it.contains(commandQuery, ignoreCase = true) }
            AlertDialog(
                onDismissRequest = { showCommandPalette = false },
                containerColor = Color(0xFF1B2633),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
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
                            Text("No matching commands", color = IdeMuted, modifier = Modifier.padding(IdeDesignTokens.Spacing.md))
                        }
                        matches.forEach { command ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        showCommandPalette = false
                                        when (command) {
                                            "New File" -> { newFileName = ""; newFileDialog = true }                                            "File Explorer" -> showExplorer = !showExplorer                                            "Terminal" -> { selectedPanel = ToolPanel.Terminal; showToolPanel = true }
                                            "Build" -> { selectedPanel = ToolPanel.Build; showToolPanel = true }
                                            "Problems" -> { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                                            "Output", "Run" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
                                            "Find" -> { searchInProject = false; searchQuery = ""; showSearch = true }
                                            "Find in Project" -> { searchInProject = true; searchQuery = ""; showSearch = true }
                                            "Format Document" -> editorController.format()
                                            "Select All" -> editorController.selectAll()
                                            "Undo" -> editorController.undo()
                                            "Redo" -> editorController.redo()
                                            "Copy" -> editorController.copy()
                                            "Cut" -> editorController.cut()
                                            "Paste" -> editorController.paste()
                                            "Toggle Word Wrap" -> editorWordWrap = !editorWordWrap
                                            "Save" -> {
                                                fileContents[activeFile] = code
                                                runCatching { projectStorage.writeFile(selectedProject, activeFile, code) }
                                                    .onSuccess { dirtyFiles.remove(activeFile); clangdSession?.saveDocument(activeFile) }
                                                    .onFailure { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                                            }
                                            "Save All" -> {
                                                fileContents[activeFile] = code
                                                dirtyFiles.toList().forEach { file ->
                                                    val content = fileContents[file] ?: ""
                                                    runCatching { projectStorage.writeFile(selectedProject, file, content) }
                                                        .onSuccess { dirtyFiles.remove(file); clangdSession?.saveDocument(file) }
                                                        .onFailure { selectedPanel = ToolPanel.Problems; showToolPanel = true }
                                                }
                                            }
                                            "Settings" -> { settingsReturnScreen = screen; screen = IdeScreen.Settings }
                                            "Sync Project" -> { selectedPanel = ToolPanel.Output; showToolPanel = true }
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
                                Spacer(Modifier.width(IdeDesignTokens.Spacing.md))
                                Text(command, color = Color(0xFFE8EDF5), fontSize = IdeDesignTokens.Typography.body)
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
                        Text(if (searchQuery.isBlank()) "Type to search " + if (searchInProject) "all project files" else "the current file" else "$matchCount match${if (matchCount == 1) "" else "es"} found", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                        if (searchInProject && pattern != null) {
                            if (projectResults.isEmpty()) Text("No matches in project files.", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                            else LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.xs)) {
                                items(projectResults, key = { it.first }) { (file, count) ->
                                    Row(Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(9.dp)).clickable { openFile(file); showSearch = false }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FileTypeIcon(file, 19.dp)
                                        Spacer(Modifier.width(9.dp))
                                        Text(file, Modifier.weight(1f), color = Color(0xFFE8EDF5), fontSize = IdeDesignTokens.Typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        if (showSourceControl) {
            AlertDialog(
                onDismissRequest = { showSourceControl = false },
                containerColor = IdeSurface,
                title = { Text("Source Control", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("WORKING TREE", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Local editor changes for this session", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                        if (dirtyFiles.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = IdeDesignTokens.Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                                IdeIcon("git", IdeGreen, 30.dp)
                                Text("Working tree clean", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold)
                                Text("Edit a file to see it listed here.", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                            }
                        } else {
                            dirtyFiles.forEach { file ->
                                Row(Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(IdeDesignTokens.Spacing.sm)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FileTypeIcon(file, 18.dp)
                                    Spacer(Modifier.width(IdeDesignTokens.Spacing.sm))
                                    Text(file, Modifier.weight(1f), color = Color(0xFFE8EDF5), fontSize = IdeDesignTokens.Typography.caption)
                                    Text("M", color = Color(0xFFE5B978), fontWeight = FontWeight.Bold, fontSize = IdeDesignTokens.Typography.caption)
                                }
                            }
                            Text("Git repository operations are not connected yet.", color = Color(0xFFE5B978), fontSize = 11.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSourceControl = false }) { Text("Done", color = IdeAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { dirtyFiles.clear(); showSourceControl = false }) { Text("Clear indicators", color = IdeMuted) }
                }
            )
        }
        if (showExtensions) {
            val extensions = listOf(
                Triple("C/C++", "Language support · clangd integration planned", "C, C++, headers"),
                Triple("CMake Tools", "Configure and build CMake projects", "CMake"),
                Triple("Python", "Python editing and language server", "Python"),
                Triple("Rust", "Rust editing and rust-analyzer", "Rust"),
                Triple("Kotlin", "Kotlin source editing", "Kotlin"),
                Triple("Web Tools", "HTML, CSS, JavaScript and TypeScript", "Web")
            ).filter { it.first.contains(extensionQuery, ignoreCase = true) || it.third.contains(extensionQuery, ignoreCase = true) }
            AlertDialog(
                onDismissRequest = { showExtensions = false },
                containerColor = IdeSurface,
                title = { Text("Extensions", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = extensionQuery,
                            onValueChange = { extensionQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Search language tools") },
                            leadingIcon = { IdeIcon("search", IdeAccent, 18.dp) }
                        )
                        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                            extensions.forEach { (name, description, tags) ->
                                Row(Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(10.dp)).padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(36.dp).background(IdeSurface, RoundedCornerShape(IdeDesignTokens.Spacing.sm)), contentAlignment = Alignment.Center) {
                                        IdeIcon("extensions", IdeAccent, 20.dp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(name, color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text(description, color = IdeMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(tags, color = IdeAccent, fontSize = 10.sp)
                                    }
                                    Text("Planned", color = IdeMuted, fontSize = 10.sp)
                                }
                            }
                            if (extensions.isEmpty()) Text("No matching extensions.", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                        }
                        Text("Extension installation is not connected; this catalog is informational.", color = Color(0xFFE5B978), fontSize = 11.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { showExtensions = false }) { Text("Done", color = IdeAccent) } }
            )
        }
        if (showProjectOverview) {
            ModalBottomSheet(
                onDismissRequest = { showProjectOverview = false },
                // Keep the terminal sheet at a stable expanded height while the
            // keyboard opens/closes. A partial anchor makes Material3 recalculate
            // the sheet position on every IME inset change, which looks like a
            // resize when Android Back hides the keyboard.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
    }

    if (newFileDialog) {
        AlertDialog(
            onDismissRequest = { newFileDialog = false; newFileName = "" },
            containerColor = IdeSurface,
            title = { Text("Create file", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("File name") },
                        placeholder = { Text("e.g. app.py, index.html, notes.md") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Any filename or extension is supported in the editor workspace.", color = IdeMuted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFileName.trim()
                    val safeName = name.substringAfterLast('/')
                    val validName = safeName.isNotBlank() && safeName != "." && safeName != ".." &&
                        safeName.none { it == 92.toChar() || it.isISOControl() }
                    if (validName && safeName !in workspaceFiles) {
                        val starter = sampleCode(safeName)
                        runCatching { projectStorage.writeFile(selectedProject, safeName, starter) }
                            .onSuccess {
                                workspaceFiles.add(safeName)
                                fileContents[safeName] = starter
                                openFile(safeName)
                            }
                    } else if (safeName in workspaceFiles) {
                        openFile(safeName)
                    }
                    newFileDialog = false
                    newFileName = ""
                }) { Text("Create", color = IdeAccent) }
            },
            dismissButton = { TextButton(onClick = { newFileDialog = false; newFileName = "" }) { Text("Cancel", color = IdeMuted) } }
        )
    }

    if (newProjectDialog) {
        val normalizedProjectName = newProjectName.trim()
        val projectNameExists = projects.any { it.name.equals(normalizedProjectName, ignoreCase = true) }
        val projectNameValid = normalizedProjectName.isNotBlank() &&
            normalizedProjectName.length <= 48 &&
            normalizedProjectName.none { it == '/' || it == '\\' || it.isISOControl() } &&
            normalizedProjectName != "." && normalizedProjectName != ".."
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                newProjectDialog = false
                newProjectName = ""
                newProjectError = null
            },
            containerColor = IdeSurface,
            title = { Text("Create project", color = Color(0xFFE8EDF5), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
                ) {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it; newProjectError = null },
                        label = { Text("Project name") },
                        placeholder = { Text("e.g. MyFirstProgram") },
                        singleLine = true,
                        isError = newProjectError != null || (normalizedProjectName.isNotEmpty() && projectNameExists),
                        supportingText = {
                            Text(
                                newProjectError ?: if (projectNameExists) "A project with this name already exists." else "Up to 48 characters; slash characters are not allowed.",
                                color = if (newProjectError != null || projectNameExists) Color(0xFFFF8A80) else IdeMuted,
                                fontSize = 11.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("PROJECT TYPE", color = IdeMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                        listOf(
                            listOf("C17", "C++20", "Python"),
                            listOf("JavaScript", "TypeScript", "Rust"),
                            listOf("Java", "Kotlin", "HTML/CSS"),
                            listOf("Shell", "Markdown")
                        ).forEach { rowLanguages ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                rowLanguages.forEach { language ->
                                    val selected = newProjectLanguage == language
                                    if (selected) {
                                        Button(onClick = { newProjectLanguage = language },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = IdeDesignTokens.Spacing.sm),
                                            modifier = Modifier.weight(1f)) { Text(language, fontSize = 11.sp, maxLines = 1) }
                                    } else {
                                        OutlinedButton(onClick = { newProjectLanguage = language },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = IdeDesignTokens.Spacing.sm),
                                            modifier = Modifier.weight(1f)) { Text(language, fontSize = 11.sp, maxLines = 1) }
                                    }
                                }
                            }
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = IdeSurfaceRaised), shape = RoundedCornerShape(IdeDesignTokens.Spacing.md)) {
                        Row(Modifier.fillMaxWidth().padding(IdeDesignTokens.Spacing.md), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IdeIcon("code", IdeGreen, IdeDesignTokens.Spacing.xl)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entryFileForLanguage(newProjectLanguage), color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("Starter file · $newProjectLanguage", color = IdeMuted, fontSize = 11.sp)
                            }
                        }
                    }
                    Text("Preview only: files are currently kept in this editor session. Disk-backed project storage is a separate integration step.",
                        color = Color(0xFFE5B978), fontSize = 11.sp, lineHeight = 15.sp)
                }
            },
            confirmButton = {
                Button(enabled = projectNameValid && !projectNameExists, onClick = {
                    val name = normalizedProjectName
                    if (!projectNameValid || projectNameExists) {
                        newProjectError = if (name.isBlank()) "Enter a project name." else "Choose a valid, unique project name."
                        return@Button
                    }
                    val entryFile = entryFileForLanguage(newProjectLanguage)
                    runCatching {
                        projectStorage.createProject(name, newProjectLanguage, entryFile, sampleCode(entryFile))
                    }.onSuccess { stored ->
                        projects.add(0, IdeProject(stored.name, stored.language, stored.path, "Just now"))
                        selectedProject = name
                        val diskFiles = projectStorage.listFiles(name)
                        workspaceFiles.clear()
                        workspaceFiles.addAll(diskFiles)
                        workspaceDirectories.clear()
                        workspaceDirectories.addAll(projectStorage.listDirectories(name))
                        fileContents.clear()
                        diskFiles.forEach { file ->
                            runCatching { projectStorage.readFile(name, file) }
                                .onSuccess { fileContents[file] = it }
                        }
                        openFiles.clear()
                        dirtyFiles.clear()
                        activeFile = entryFile
                        code = fileContents[entryFile] ?: sampleCode(entryFile)
                        openFile(entryFile)
                        newProjectDialog = false
                        newProjectName = ""
                        newProjectError = null
                        screen = IdeScreen.Workspace
                    }.onFailure { error ->
                        newProjectError = error.message ?: "Could not create project."
                    }
                }) { Text("Create project") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    newProjectDialog = false
                    newProjectName = ""
                    newProjectError = null
                }) { Text("Cancel") }
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
    val primaryFile = entryFileForLanguage(projectLanguage)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Project Overview", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("PROJECT SUMMARY", color = IdeMuted, fontSize = 10.sp)
            }
            Text("×", Modifier.clickable(onClick = onClose).padding(6.dp), color = IdeAccent, fontSize = IdeDesignTokens.Typography.title)
        }
        Card(colors = CardDefaults.cardColors(containerColor = IdeSurfaceRaised), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(IdeDesignTokens.Spacing.lg), verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                Text(projectName, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("Projects/$projectName", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                Row(horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(projectLanguage, color = IdeAccent, fontWeight = FontWeight.SemiBold, fontSize = IdeDesignTokens.Typography.caption)
                    Text("• Workspace", color = IdeMuted, fontSize = 11.sp)
                }
            }
        }
        Text("PROJECT CONTENTS", color = IdeAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
            OverviewMetric("—", "Files", Modifier.weight(1f))
            OverviewMetric(projectLanguage, "Language", Modifier.weight(1f))
        }
        Text("Quick open", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold)
        OverviewFileRow(primaryFile, "Entry file") { onOpenFile(primaryFile) }
        OverviewFileRow("README.md", "Project notes") { onOpenFile("README.md") }
        OverviewFileRow(".gitignore", "Ignored files") { onOpenFile(".gitignore") }
        OutlinedButton(onClick = onBrowseFiles, modifier = Modifier.fillMaxWidth()) { Text("Browse project files") }
        Text("Language services are selected from the active file and workspace configuration.", color = IdeMuted, fontSize = 11.sp)
    }
}

@Composable
private fun OverviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(IdeSurfaceRaised, RoundedCornerShape(10.dp)).padding(vertical = 13.dp, horizontal = IdeDesignTokens.Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.xs)
    ) {
        Text(value, color = IdeAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = IdeMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun OverviewFileRow(name: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(IdeSurfaceRaised, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = IdeDesignTokens.Spacing.md, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (name.endsWith(".h")) "◇" else if (name.endsWith(".cpp")) "C++" else "C",
            color = IdeAccent, fontWeight = FontWeight.Bold, fontSize = IdeDesignTokens.Typography.caption, modifier = Modifier.width(38.dp))
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
            if (files.isNotEmpty()) Text("Clear", Modifier.clickable(onClick = onClear).padding(IdeDesignTokens.Spacing.sm), color = IdeAccent)
            Text("Done", Modifier.clickable(onClick = onClose).padding(IdeDesignTokens.Spacing.sm), color = IdeMuted)
        }
        if (files.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = IdeDesignTokens.Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)
            ) {
                Text("◷", color = IdeAccent, fontSize = 36.sp)
                Text("No recent files yet", fontWeight = FontWeight.SemiBold)
                Text("Files you open will appear here.", color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
                OutlinedButton(onClick = { onOpenFile(activeFile) }) { Text("Return to current file") }
            }
        } else {
            displayFiles.distinct().forEach { file ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (file == activeFile) Color(0xFF173D5C) else IdeSurfaceRaised, RoundedCornerShape(10.dp))
                        .clickable { onOpenFile(file) }
                        .padding(horizontal = IdeDesignTokens.Spacing.md, vertical = 13.dp),
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
        Text(label, color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(IdeDesignTokens.Spacing.sm))
        Text(value, color = Color(0xFFE8EDF5), fontSize = IdeDesignTokens.Typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WelcomeScreen(onNext: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(IdeBackground).padding(horizontal = IdeDesignTokens.Spacing.xl, vertical = IdeDesignTokens.Spacing.xxl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.lg)
        ) {
            Box(
                Modifier.size(88.dp).background(IdeSurface, RoundedCornerShape(IdeDesignTokens.Shape.large)),
                contentAlignment = Alignment.Center
            ) {
                Text("</>", color = IdeAccent, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("Code Studio", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = IdeDesignTokens.Color.TextPrimary)
            Text(
                "Your native coding workspace for Android",
                color = IdeMuted,
                fontSize = IdeDesignTokens.Typography.body,
                textAlign = TextAlign.Center
            )
            Column(
                Modifier.fillMaxWidth().background(IdeSurface, RoundedCornerShape(IdeDesignTokens.Shape.large)).padding(IdeDesignTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
            ) {
                WelcomeFeature("C / C++ and Python", "Build your projects in a focused editor")
                WelcomeFeature("Project workspace", "Manage files, tabs and recent projects")
                WelcomeFeature("Integrated terminal", "Install a compatible private runtime from Settings")
            }
            Text(
                "One-time setup · You can change preferences and install the runtime later in Settings.",
                color = IdeMuted,
                fontSize = IdeDesignTokens.Typography.caption,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(IdeDesignTokens.Shape.medium)
            ) {
                Text("Get started  →", fontSize = IdeDesignTokens.Typography.body, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun WelcomeFeature(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.xxs)) {
        Text(title, color = IdeDesignTokens.Color.TextPrimary, fontSize = IdeDesignTokens.Typography.body, fontWeight = FontWeight.SemiBold)
        Text(description, color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
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
    var selectedFilter by rememberSaveable { mutableStateOf("All") }
    val filters = listOf("All", "C/C++", "Python", "Web", "Other")
    fun category(language: String): String = when (language) {
        "C17", "C++20" -> "C/C++"
        "Python" -> "Python"
        "JavaScript", "TypeScript", "HTML/CSS" -> "Web"
        else -> "Other"
    }
    val visible = projects.filter { project ->
        (project.name.contains(query, ignoreCase = true) ||
            project.language.contains(query, ignoreCase = true) ||
            project.path.contains(query, ignoreCase = true)) &&
            (selectedFilter == "All" || category(project.language) == selectedFilter)
    }

    Scaffold(
        containerColor = IdeBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("My workspace", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Build something great today", color = IdeMuted, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        IdeIcon("settings", IdeMuted, 21.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = IdeBackground,
                    titleContentColor = Color(0xFFF2F6FC)
                )
            )
        },
        floatingActionButton = {
            Button(
                onClick = onNew,
                shape = RoundedCornerShape(IdeDesignTokens.Spacing.lg),
                modifier = Modifier.height(52.dp)
            ) {
                IdeIcon("add", Color.White, 18.dp)
                Spacer(Modifier.width(IdeDesignTokens.Spacing.sm))
                Text("New project", fontWeight = FontWeight.SemiBold)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = IdeDesignTokens.Spacing.lg, end = IdeDesignTokens.Spacing.lg, top = IdeDesignTokens.Spacing.sm, bottom = 100.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF172A40)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF294763))
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("YOUR NEXT IDEA\nSTARTS HERE", color = Color(0xFFF2F6FC), fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                                Text("Create a project and start coding on your device.", color = Color(0xFFB7C7D9), fontSize = IdeDesignTokens.Typography.caption, lineHeight = 17.sp)
                            }
                            Box(
                                Modifier.size(70.dp).background(Color(0xFF254663), RoundedCornerShape(20.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("</>", color = IdeGreen, fontFamily = FontFamily.Monospace, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WorkspaceMetricCard(
                        modifier = Modifier.weight(1f),
                        value = projects.size.toString(),
                        label = "Projects",
                        icon = "folder",
                        accent = IdeAccent
                    )
                    WorkspaceMetricCard(
                        modifier = Modifier.weight(1f),
                        value = projects.map { it.language }.distinct().size.toString(),
                        label = "Languages",
                        icon = "code",
                        accent = IdeGreen
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search projects, languages or folders", fontSize = 13.sp) },
                    leadingIcon = { IdeIcon("search", IdeAccent, 19.dp) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            Text("×", Modifier.clickable { query = "" }.padding(horizontal = IdeDesignTokens.Spacing.md), color = IdeMuted, fontSize = 22.sp)
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = IdeSurface,
                        focusedContainerColor = IdeSurface,
                        unfocusedBorderColor = IdeSurfaceRaised,
                        focusedBorderColor = IdeAccent
                    )
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)
                ) {
                    filters.forEach { filter ->
                        val selected = selectedFilter == filter
                        if (selected) {
                            Button(
                                onClick = { selectedFilter = filter },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 7.dp),
                                shape = RoundedCornerShape(50)
                            ) { Text(filter, fontSize = IdeDesignTokens.Typography.caption) }
                        } else {
                            OutlinedButton(
                                onClick = { selectedFilter = filter },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 15.dp, vertical = 7.dp),
                                shape = RoundedCornerShape(50)
                            ) { Text(filter, fontSize = IdeDesignTokens.Typography.caption, color = IdeMuted) }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Recent projects", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF2F6FC))
                        Text("Pick up where you left off", fontSize = 11.sp, color = IdeMuted)
                    }
                    Text("${visible.size} shown", color = IdeMuted, fontSize = 11.sp)
                }
            }
            if (visible.isEmpty()) {
                item { EmptyProjects(onNew = onNew) }
            } else {
                items(visible, key = { it.name }) { project ->
                    val accent = when (category(project.language)) {
                        "C/C++" -> Color(0xFF83B8F2)
                        "Python" -> Color(0xFFE5C56E)
                        "Web" -> Color(0xFFE58A75)
                        else -> IdeGreen
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(project) },
                        shape = RoundedCornerShape(17.dp),
                        colors = CardDefaults.cardColors(containerColor = IdeSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263A4D))
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(46.dp).background(accent.copy(alpha = 0.13f), RoundedCornerShape(13.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        when (project.language) {
                                            "C17" -> "C"
                                            "C++20" -> "C++"
                                            "Python" -> "Py"
                                            "JavaScript" -> "JS"
                                            "TypeScript" -> "TS"
                                            "Rust" -> "Rs"
                                            "Java" -> "J"
                                            "Kotlin" -> "K"
                                            "HTML/CSS" -> "5"
                                            "Shell" -> "\\$"
                                            else -> "M↓"
                                        },
                                        color = accent,
                                        fontSize = if (project.language == "C++20") IdeDesignTokens.Typography.caption else 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(IdeDesignTokens.Spacing.md))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.xs)) {
                                    Text(project.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color(0xFFF2F6FC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(project.language, color = accent, fontSize = IdeDesignTokens.Typography.caption)
                                }
                                Box(
                                    Modifier.size(34.dp).background(IdeSurfaceRaised, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) { Text("›", color = Color(0xFFE8EDF5), fontSize = IdeDesignTokens.Typography.headline) }
                            }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color(0xFF121D28)).padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IdeIcon("folder", IdeMuted, 15.dp)
                                Spacer(Modifier.width(IdeDesignTokens.Spacing.sm))
                                Text(project.path, Modifier.weight(1f), color = IdeMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(IdeGreen, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("Updated ${project.updated}", color = IdeMuted, fontSize = 10.sp)
                                Spacer(Modifier.weight(1f))
                                Text("OPEN PROJECT", color = IdeAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceMetricCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    icon: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = IdeSurface),
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263A4D))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(36.dp).background(accent.copy(alpha = 0.13f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                IdeIcon(icon, accent, 19.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.xxs)) {
                Text(value, color = Color(0xFFF2F6FC), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(label, color = IdeMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EmptyProjects(onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 52.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
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
    lspSession: ClangdProjectSession?,
    editorFontSize: Float,
    editorWordWrap: Boolean,
    editorTheme: String,
    onMenu: () -> Unit,
    onExplorer: () -> Unit,
    onFile: (String) -> Unit,
    onAction: (String) -> Unit,    selectedPanel: ToolPanel,
    showToolPanel: Boolean,
    fullScreen: Boolean,
    onToggleFullScreen: () -> Unit,
    onCloseToolPanel: () -> Unit,
    onPanel: (ToolPanel) -> Unit,
    onOpenSettings: () -> Unit
) {
    val lspStatus by lspSession?.status?.collectAsState()
        ?: remember { mutableStateOf(EditorLspStatus("clangd unavailable", "Open a saved C/C++ project to start language services.", error = true)) }
    val editorProblems by lspSession?.problems?.collectAsState()
        ?: remember { mutableStateOf(emptyList<EditorProblem>()) }
    var cursorLine by rememberSaveable(activeFile) { mutableStateOf(1) }
    var cursorColumn by rememberSaveable(activeFile) { mutableStateOf(1) }
    var showGoToLineDialog by rememberSaveable { mutableStateOf(false) }
    var goToLineInput by rememberSaveable { mutableStateOf("") }
    var pendingProblem by remember { mutableStateOf<EditorProblem?>(null) }

    LaunchedEffect(activeFile, pendingProblem) {
        val target = pendingProblem
        if (target != null && target.filePath == activeFile) {
            editorController.selectPosition(target.line, target.column)
            pendingProblem = null
        }
    }

    IdeMasterShellUi(
        modifier = Modifier.fillMaxSize().imePadding(),
        bottomPanelVisible = showToolPanel,
        onCloseBottomPanel = onCloseToolPanel,
        selectedPanel = selectedPanel.name.uppercase(),
        onSelectPanel = { label -> onPanel(ToolPanel.valueOf(label.lowercase().replaceFirstChar { it.titlecase() })) },
        workspaceContent = {
                Scaffold(
        containerColor = IdeBackground,
        topBar = {
            TopAppBar(
                modifier = Modifier.height(42.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FileTypeIcon(activeFile, 21.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(activeFile + if (dirtyFiles.contains(activeFile)) "  ●" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(projectName, fontSize = 10.sp, color = IdeMuted, maxLines = 1)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) { IdeIcon("menu", IdeAccent, 20.dp) }
                },
                actions = {
                    IconButton(onClick = { onAction("Undo") }, modifier = Modifier.size(IdeDesignTokens.Spacing.xxl)) { IdeIcon("undo", IdeAccent, 17.dp) }
                    IconButton(onClick = { onAction("Redo") }, modifier = Modifier.size(IdeDesignTokens.Spacing.xxl)) { IdeIcon("redo", IdeAccent, 17.dp) }
                    IconButton(onClick = { onAction("Save") }, modifier = Modifier.size(IdeDesignTokens.Spacing.xxl)) { IdeIcon("save", IdeAccent, IdeDesignTokens.Spacing.lg) }
                    var showOverflow by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showOverflow = true }) { IdeIcon("more", IdeMuted, 20.dp) }
                        androidx.compose.material3.DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            listOf("Command Palette", "Find", "Find in Project", "Select All", "Copy", "Cut", "Paste", "Undo", "Redo", "Toggle Word Wrap", "Save All", "File Explorer", "Terminal", "Build", "Problems", "Output", if (fullScreen) "Exit Full Screen" else "Full Screen", "Settings", "Sync Project", "Cancel Build").forEach { action ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(action) },
                                    leadingIcon = { IdeIcon(when (action) { "Find", "Find in Project", "Command Palette" -> "search"; "File Explorer" -> "folder"; "Terminal" -> "terminal"; "Build", "Cancel Build" -> "build"; "Problems" -> "problems"; "Output", "Sync Project" -> "output"; "Settings" -> "settings"; "Full Screen", "Exit Full Screen" -> "fullscreen"; else -> "save" }, IdeAccent, 18.dp) },
                                    onClick = { showOverflow = false; when (action) { "File Explorer" -> onExplorer(); "Full Screen", "Exit Full Screen" -> onToggleFullScreen(); "Terminal", "Build", "Problems", "Output" -> onAction(action); else -> onAction(action) } }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = IdeSurface)
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(IdeBackground)
        ) {
            IdeMenuBar(
                onAction = onAction,
                onGoToLine = { goToLineInput = cursorLine.toString(); showGoToLineDialog = true },
                onToggleFullScreen = onToggleFullScreen
            )
            // Compact AndroidIDE-style open-file strip: never let a tab consume the full row width.
            Row(
                Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF151A22)),
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
                    Modifier.clickable { onFile(if (activeFile == "README.md") ".gitignore" else "README.md") }
                        .padding(horizontal = IdeDesignTokens.Spacing.md, vertical = IdeDesignTokens.Spacing.sm),
                    color = IdeAccent, fontSize = 20.sp
                )
            }
            // The editor owns all remaining vertical space, like AndroidIDE's workspace.
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0F1218))) {
                SoraCodeEditor(
                    code = code,
                    onCodeChange = onCodeChange,
                    modifier = Modifier.fillMaxSize(),
                    controller = editorController,
                    fontSize = editorFontSize,
                    wordWrap = editorWordWrap,
                    themeName = editorTheme,
                    fileName = activeFile,
                    lspSession = lspSession,
                    onCursorPositionChange = { line, column -> cursorLine = line; cursorColumn = column }
                )
            }
        }
    }
        },
        statusBarContent = {
            Row(
                Modifier.fillMaxWidth().height(20.dp).background(Color(0xFF007ACC)).padding(horizontal = 7.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (fullScreen) "⛶" else "⌘", Modifier.clickable(onClick = onToggleFullScreen), color = Color.White, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text("main", color = Color.White, fontSize = 10.sp)
                Spacer(Modifier.width(IdeDesignTokens.Spacing.md))
                Text(if (dirtyFiles.contains(activeFile)) "● Unsaved" else "✓ ${projectName}", color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text("Ln " + cursorLine + ", Col " + cursorColumn, color = Color.White, fontSize = 10.sp)
                Spacer(Modifier.width(10.dp))
                Text(lspStatus.label, color = if (lspStatus.error) Color(0xFFFF8A80) else if (lspStatus.connected) Color(0xFFB7F0C0) else Color.White, fontSize = 9.sp, maxLines = 1)
                val errorCount = editorProblems.count { it.severity == "Error" }
                val warningCount = editorProblems.count { it.severity == "Warning" }
                if (errorCount > 0 || warningCount > 0) {
                    Text(
                        (if (errorCount > 0) "×" + errorCount else "") +
                            (if (warningCount > 0) "  !" + warningCount else ""),
                        Modifier.clickable { onPanel(ToolPanel.Problems) },
                        color = if (errorCount > 0) Color(0xFFFF8A80) else Color(0xFFE5B978),
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(when (activeFile.substringAfterLast(".", "").lowercase()) { "cpp", "cc", "cxx", "hpp", "h" -> "C++"; "c" -> "C"; "py" -> "Python"; "js", "mjs" -> "JavaScript"; "ts", "tsx" -> "TypeScript"; "html", "htm" -> "HTML"; "css" -> "CSS"; "json" -> "JSON"; "md" -> "Markdown"; "sh", "bash" -> "Shell"; "rs" -> "Rust"; else -> "Plain Text" } + "  •  UTF-8", color = Color.White, fontSize = 10.sp)
            }
        },
        terminalDock = {
            ToolPanelContent(
                selected = selectedPanel,
                projectName = projectName,
                activeFile = activeFile,
                code = code,
                onSelect = onPanel,
                onClose = onCloseToolPanel,
                onOpenSettings = onOpenSettings,
                problems = editorProblems,
                lspStatus = lspStatus,
                onProblemSelected = { problem ->
                    if (problem.filePath == activeFile) {
                        editorController.selectPosition(problem.line, problem.column)
                    } else {
                        pendingProblem = problem
                        onFile(problem.filePath)
                    }
                }
            )
        }
    )
    if (showGoToLineDialog) {
        AlertDialog(
            onDismissRequest = { showGoToLineDialog = false },
            containerColor = IdeSurface,
            title = { Text("Go to Line", color = Color(0xFFE8EDF5), fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = goToLineInput,
                    onValueChange = { value -> goToLineInput = value.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    label = { Text("Line number") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    goToLineInput.toIntOrNull()?.takeIf { it > 0 }?.let(editorController::selectLine)
                    showGoToLineDialog = false
                }) { Text("Go", color = IdeAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showGoToLineDialog = false }) { Text("Cancel", color = IdeMuted) }
            }
        )
    }
}

@Composable
private fun IdeMenuBar(
    onAction: (String) -> Unit,
    onGoToLine: () -> Unit,
    onToggleFullScreen: () -> Unit
) {
    val menus = listOf(
        "File" to listOf("New File", "Save", "Save All"),
        "Edit" to listOf("Undo", "Redo", "Cut", "Copy", "Paste", "Select All", "Find", "Find in Project", "Format Document"),
        "Selection" to listOf("Select All"),
        "View" to listOf("Toggle Word Wrap", "Problems", "Output", "Full Screen"),
        "Go" to listOf("Go to Line..."),
        "Run" to listOf("Build", "Run", "Problems"),
        "Terminal" to listOf("Terminal"),
        "Help" to listOf("Settings")
    )
    var expandedMenu by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(Color(0xFF20252D))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        menus.forEach { (menuName, actions) ->
            Box {
                Text(
                    menuName,
                    Modifier.clickable { expandedMenu = if (expandedMenu == menuName) null else menuName }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    color = if (expandedMenu == menuName) Color.White else Color(0xFFCBD3DE),
                    fontSize = 11.sp
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expandedMenu == menuName,
                    onDismissRequest = { expandedMenu = null }
                ) {
                    actions.forEach { action ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(action) },
                            onClick = {
                                expandedMenu = null
                                when (action) {
                                    "Go to Line..." -> onGoToLine()
                                    "Full Screen" -> onToggleFullScreen()
                                    else -> onAction(action)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileExplorerSheet(projectName: String, activeFile: String, files: List<String>, directories: List<String>, onOpenFile: (String) -> Unit, onCreateFile: (String) -> Unit, onCreateFolder: suspend (String) -> Unit, onClose: () -> Unit, sidebarMode: Boolean = false) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(projectName) { mutableStateOf("") }
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
            Column(Modifier.weight(1f)) { Text("EXPLORER", color = Color(0xFFCAD4E0), fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(projectName, color = IdeMuted, fontSize = 10.sp) }
            IconButton(onClick = { query = "" }, modifier = Modifier.size(30.dp)) { IdeIcon("refresh", IdeMuted, 16.dp) }
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) { IdeIcon("close", IdeMuted, 16.dp) }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp), singleLine = true, shape = androidx.compose.ui.graphics.RectangleShape, placeholder = { Text("Search files", fontSize = 12.sp) }, leadingIcon = { IdeIcon("search", IdeAccent, 17.dp) })
        ExplorerDirectoryRow(projectName, ".", 0, true, expanded = "." in expanded, onClick = { expanded = if ("." in expanded) emptySet() else setOf(".") }, onCreateFile = { createTarget = it; newEntryName = ""; fileDialog = true }, onCreateFolder = { createTarget = it; newEntryName = ""; folderDialog = true })
        if ("." in expanded || query.isNotBlank()) nodes.forEach { node ->
            if (node.folder) ExplorerDirectoryRow(node.path.substringAfterLast("/"), node.path, node.depth + 1, false, expanded = node.path in expanded || query.isNotBlank(), onClick = { expanded = if (node.path in expanded) expanded - node.path else expanded + node.path }, onCreateFile = { createTarget = it; newEntryName = ""; fileDialog = true }, onCreateFolder = { createTarget = it; newEntryName = ""; folderDialog = true })
            else ExplorerFileRow(node.path.substringAfterLast("/"), node.path, activeFile == node.path, ((node.depth + 1) * 14).dp) { onOpenFile(node.path) }
        }
        if (nodes.isEmpty()) Text(if (query.isBlank()) "This folder is empty" else "No matching files", Modifier.fillMaxWidth().padding(18.dp), color = IdeMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (fileDialog || folderDialog) {
            val folder = folderDialog
            val prefix = if (createTarget == ".") "" else "$createTarget/"
            AlertDialog(onDismissRequest = { fileDialog = false; folderDialog = false }, containerColor = IdeSurface, title = { Text(if (folder) "New Folder" else "New File", color = Color(0xFFE8EDF5)) },
                text = { Column { Text("In " + if (createTarget == ".") projectName else createTarget, color = IdeMuted, fontSize = 10.sp); OutlinedTextField(value = newEntryName, onValueChange = { newEntryName = it }, singleLine = true, label = { Text(if (folder) "Folder name" else "File name") }) } },
                confirmButton = { TextButton(onClick = {
                    val name = newEntryName.trim(); val target = prefix + name
                    when {
                        name.isBlank() || name.contains("/") || name.contains("\\") || name == "." || name == ".." -> entryMessage = "Enter a valid name without path separators."
                        target in files || target in directories -> entryMessage = "An item with this name already exists."
                        folder -> scope.launch { runCatching { onCreateFolder(target) }.onSuccess { expanded = expanded + createTarget + target; entryMessage = "Folder created." }.onFailure { entryMessage = it.message ?: "Unable to create folder." } }
                        else -> { onCreateFile(target); entryMessage = "File created." }
                    }; fileDialog = false; folderDialog = false
                }) { Text("Create", color = IdeAccent) } },
                dismissButton = { TextButton(onClick = { fileDialog = false; folderDialog = false }) { Text("Cancel", color = IdeMuted) } })
        }
        entryMessage?.let { AlertDialog(onDismissRequest = { entryMessage = null }, containerColor = IdeSurface, title = { Text("Explorer") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { entryMessage = null }) { Text("OK") } }) }
    }
}

@Composable
private fun ExplorerDirectoryRow(label: String, path: String, depth: Int, root: Boolean, expanded: Boolean, onClick: () -> Unit, onCreateFile: (String) -> Unit, onCreateFolder: (String) -> Unit) {
    var menu by remember(path) { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().padding(start = (depth * 14).dp).background(if (root) IdeSurfaceRaised else Color.Transparent).combinedClickable(onClick = onClick, onLongClick = { menu = true }).height(28.dp).padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IdeIcon(if (expanded) "chevronDown" else "chevronRight", IdeMuted, 13.dp); Spacer(Modifier.width(3.dp)); IdeIcon("folder", Color(0xFFE4BE79), 16.dp); Spacer(Modifier.width(7.dp))
            Text(label, Modifier.weight(1f), color = if (root) Color(0xFFE8EDF5) else Color(0xFFDCE3EC), fontSize = 11.sp, fontWeight = if (root) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("New File") }, leadingIcon = { IdeIcon("add", IdeAccent, 16.dp) }, onClick = { menu = false; onCreateFile(path) })
            androidx.compose.material3.DropdownMenuItem(text = { Text("New Folder") }, leadingIcon = { IdeIcon("folder", IdeAccent, 16.dp) }, onClick = { menu = false; onCreateFolder(path) })
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
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start = indent)
            .background(if (selected) Color(0xFF1D3047) else Color.Transparent)
            .clickable(onClick = onClick)
            .height(28.dp)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileTypeIcon(name, 15.dp, Modifier.width(22.dp))
        Text(name, Modifier.weight(1f), color = if (selected) IdeAccent else Color(0xFFE8EDF5), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Text("●", color = IdeAccent, fontSize = 8.sp)
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
             .widthIn(min = 64.dp, max = 160.dp)
            .clickable(onClick = onClick)
            .background(if (selected) IdeSurface else Color.Transparent)
            .padding(horizontal = IdeDesignTokens.Spacing.sm, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FileTypeIcon(name, IdeDesignTokens.Spacing.lg)
            Text(name, color = if (selected) Color(0xFFE8EDF5) else IdeMuted, fontSize = IdeDesignTokens.Typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (dirty) Text("●", color = Color(0xFFE5B978), fontSize = 9.sp)
            IdeIcon("close", IdeMuted, 13.dp, Modifier.clickable(onClick = onClose).padding(start = IdeDesignTokens.Spacing.xs))
        }
        Spacer(Modifier.height(IdeDesignTokens.Spacing.xs))
        Box(Modifier.fillMaxWidth().height(IdeDesignTokens.Spacing.xxs).background(if (selected) IdeAccent else Color.Transparent))
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
        Box(Modifier.width(18.dp).height(IdeDesignTokens.Spacing.xxs).background(if (selected) IdeAccent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}

private val CompilerDiagnosticPattern =
    Regex("""^(.+?):(\d+):(\d+):\s*(fatal error|error|warning|note):\s*(.*)$""")

@Composable
private fun ToolPanelContent(
    selected: ToolPanel,
    projectName: String,
    activeFile: String,
    code: String,
    onSelect: (ToolPanel) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    problems: List<EditorProblem>,
    lspStatus: EditorLspStatus,
    onProblemSelected: (EditorProblem) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val buildScope = rememberCoroutineScope()
    val buildRunner = remember(context) { EmbeddedCppBuildRunner(context) }
    var building by remember(projectName, activeFile) { mutableStateOf(false) }
    var buildResult by remember(projectName, activeFile) { mutableStateOf<EmbeddedCppBuildRunner.Result?>(null) }
    var buildError by remember(projectName, activeFile) { mutableStateOf<String?>(null) }
    val compilerProblems = remember(buildResult, activeFile) {
        buildResult?.compilerOutput.orEmpty().lineSequence().mapNotNull { outputLine ->
            val match = CompilerDiagnosticPattern.find(outputLine) ?: return@mapNotNull null
            val reportedPath = match.groupValues[1].substringAfter("/workspace/", match.groupValues[1])
            EditorProblem(
                filePath = if (reportedPath.startsWith("/")) activeFile else reportedPath.removePrefix("./"),
                line = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
                column = match.groupValues[3].toIntOrNull() ?: 1,
                severity = when (match.groupValues[4]) {
                    "fatal error", "error" -> "Error"
                    "warning" -> "Warning"
                    else -> "Information"
                },
                message = match.groupValues[5]
            )
        }.toList()
    }
    val visibleProblems = remember(problems, compilerProblems) {
        (problems + compilerProblems).distinctBy {
            listOf(it.filePath, it.line.toString(), it.column.toString(), it.severity, it.message).joinToString("|")
        }.sortedWith(compareBy<EditorProblem>({ it.filePath }, { it.line }, { it.column }))
    }

    // The terminal view can hold focus and consume Android Back as Escape.
    // Give the open tool panel an explicit Back handler so it always dismisses.
    BackHandler(onBack = onClose)

    Column(
        Modifier.fillMaxWidth().fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        when (selected) {
            ToolPanel.Terminal -> {
                TermuxLinuxTerminalPanel(
                    Modifier.weight(1f).fillMaxWidth(),
                    onOpenSettings = onOpenSettings,
                    onCloseTerminal = onClose
                )
            }
            ToolPanel.Build -> {
                val extension = activeFile.substringAfterLast('.', "").lowercase()
                val supportedSource = extension in setOf("c", "cc", "cpp", "cxx", "c++")
                ToolStatusCard(
                    "C / C++ BUILD",
                    when {
                        building -> "Building and running…"
                        buildResult?.runSucceeded == true -> "Build and run succeeded"
                        buildResult?.compileSucceeded == true -> "Compiled; program returned a non-zero exit code"
                        buildResult?.timedOut == true -> "Command timed out"
                        buildResult?.error != null -> "Build failed"
                        buildResult != null -> "Compilation failed"
                        else -> "Ready to build"
                    },
                    "Uses the selected Linux runtime and saved C/C++ Language Settings.",
                    if (supportedSource) "$projectName / $activeFile" else "Open a .c, .cc, .cpp or .cxx source file",
                    if (buildResult?.runSucceeded == true) IdeGreen else IdeAccent
                )
                Button(
                    enabled = supportedSource && !building,
                    onClick = {
                        building = true
                        buildResult = null
                        buildError = null
                        buildScope.launch {
                            try {
                                buildResult = withContext(Dispatchers.IO) {
                                    ProjectStorage(context).writeFile(projectName, activeFile, code)
                                    buildRunner.buildAndRun(projectName, activeFile)
                                }
                            } catch (error: Exception) {
                                buildError = error.message ?: error.javaClass.simpleName
                            } finally {
                                building = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (building) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else IdeIcon("play", Color.White, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (building) "Building…" else "Build & Run")
                }
                buildError?.let { ToolStatusCard("BUILD ERROR", "Unable to build", it, "Check runtime and workspace", Color(0xFFE5B978)) }
                buildResult?.let { result ->
                    Text("COMPILER OUTPUT", color = IdeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (result.compilerOutput.isNotBlank()) {
                        Text(
                            result.compilerOutput,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                                .background(Color(0xFF0B0F14), RoundedCornerShape(8.dp)).padding(8.dp),
                            color = Color(0xFFD5DCE5),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    } else Text("No compiler messages.", color = IdeMuted, fontSize = 11.sp)
                    result.error?.let { Text(it, color = Color(0xFFE5B978), fontSize = 11.sp) }
                    if (result.compileSucceeded) {
                        Text("PROGRAM OUTPUT", color = IdeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            result.programOutput.ifBlank { "(no output)" },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                                .background(Color(0xFF0B0F14), RoundedCornerShape(8.dp)).padding(8.dp),
                            color = Color(0xFFD5DCE5),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        "Compiler exit: ${result.compileExitCode ?: "—"} · Program exit: ${result.runExitCode ?: "—"}",
                        color = IdeMuted,
                        fontSize = 10.sp
                    )
                }
                if (!supportedSource) {
                    Text("Build & Run currently supports C and C++ source files. Header files and project build scripts are not executable targets.", color = IdeMuted, fontSize = 11.sp)
                }
            }
            ToolPanel.Problems -> {
                val errors = visibleProblems.count { it.severity == "Error" }
                val warnings = visibleProblems.count { it.severity == "Warning" }
                ToolStatusCard(
                    "DIAGNOSTICS",
                    when {
                        lspStatus.error -> "Language server unavailable"
                        visibleProblems.isEmpty() && lspStatus.connected -> "No problems"
                        visibleProblems.isEmpty() -> "Waiting for diagnostics"
                        else -> visibleProblems.size.toString() + " problem(s)"
                    },
                    lspStatus.detail,
                    errors.toString() + " errors  ·  " + warnings + " warnings  ·  " + visibleProblems.size + " total",
                    when {
                        errors > 0 -> Color(0xFFFF8A80)
                        warnings > 0 -> Color(0xFFE5B978)
                        lspStatus.connected -> IdeGreen
                        else -> IdeAccent
                    }
                )
                if (visibleProblems.isEmpty()) {
                    Text(
                        if (lspStatus.error) lspStatus.detail else "clangd diagnostics will appear here as you edit C/C++ files.",
                        color = IdeMuted,
                        fontSize = 11.sp
                    )
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(visibleProblems) { problem ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onProblemSelected(problem) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IdeIcon(
                                    if (problem.severity == "Error") "error" else if (problem.severity == "Warning") "alert-triangle" else "info",
                                    if (problem.severity == "Error") Color(0xFFFF8A80) else if (problem.severity == "Warning") Color(0xFFE5B978) else IdeAccent,
                                    16.dp
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(problem.message, color = Color(0xFFE8EDF5), fontSize = 11.sp)
                                    Text(
                                        problem.filePath + ":" + problem.line + ":" + problem.column,
                                        color = IdeMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            HorizontalDivider(color = IdeSurfaceRaised)
                        }
                    }
                }
            }
            ToolPanel.Output -> {
                val result = buildResult
                ToolStatusCard(
                    "RUN OUTPUT",
                    when {
                        building -> "Build and run in progress…"
                        result == null -> "No execution yet"
                        result.runSucceeded -> "Program completed successfully"
                        result.timedOut -> "Program timed out"
                        result.error != null -> "Execution failed"
                        result.compileExitCode != 0 -> "Compilation failed"
                        else -> "Program exited with code " + (result.runExitCode ?: "unknown")
                    },
                    result?.error ?: "Output from the latest Build & Run operation.",
                    if (result == null) "Run target: Ubuntu / PRoot" else
                        "Compile exit: " + (result.compileExitCode ?: "—") + "  ·  Program exit: " + (result.runExitCode ?: "—"),
                    if (result?.runSucceeded == true) IdeGreen else if (result?.error != null || result?.timedOut == true) Color(0xFFFF8A80) else IdeAccent
                )
                if (result == null) {
                    Text("Open Build and run a C/C++ source file. The latest compiler messages and program output will appear here.", color = IdeMuted, fontSize = 11.sp)
                } else {
                    if (result.compilerOutput.isNotBlank()) {
                        Text("COMPILER MESSAGES", color = IdeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            result.compilerOutput,
                            Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                                .background(Color(0xFF0B0F14), RoundedCornerShape(8.dp)).padding(8.dp),
                            color = Color(0xFFD5DCE5),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Text("PROGRAM OUTPUT", color = IdeMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        result.programOutput.ifBlank { "(no program output)" },
                        Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                            .background(Color(0xFF0B0F14), RoundedCornerShape(8.dp)).padding(8.dp),
                        color = Color(0xFFD5DCE5),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            }
        }
        if (selected == ToolPanel.Problems && !lspStatus.connected && !lspStatus.error) {
            Text("Waiting for the first C/C++ document to initialize clangd.", color = IdeMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ToolStatusCard(
    eyebrow: String,
    status: String,    description: String,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
                Box(Modifier.size(9.dp).background(accent, RoundedCornerShape(50)))
                Text(status, color = Color(0xFFE8EDF5), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(description, color = IdeMuted, fontSize = IdeDesignTokens.Typography.caption)
            HorizontalDivider(color = IdeSurfaceRaised)
            Text(detail, color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)        }
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
            Text(title, color = Color(0xFFE8EDF5), fontSize = IdeDesignTokens.Typography.caption, fontWeight = FontWeight.Medium)
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
            "git" -> { line(.3f,.2f,.3f,.8f); line(.3f,.38f,.7f,.38f); line(.7f,.38f,.7f,.72f); circle(.3f,.2f,.09f); circle(.3f,.8f,.09f); circle(.7f,.38f,.09f); circle(.7f,.72f,.09f) }
            "extensions" -> { rect(.12f,.12f,.42f,.42f); rect(.58f,.12f,.88f,.42f); rect(.12f,.58f,.42f,.88f); rect(.58f,.58f,.88f,.88f); line(.42f,.27f,.58f,.27f); line(.42f,.73f,.58f,.73f); line(.27f,.42f,.27f,.58f); line(.73f,.42f,.73f,.58f) }
            "refresh" -> { circle(.5f,.5f,.34f); line(.63f,.16f,.84f,.18f); line(.84f,.18f,.82f,.39f); line(.18f,.61f,.16f,.82f); line(.16f,.82f,.37f,.8f) }
            "fullscreen" -> { line(.15f,.38f,.15f,.15f); line(.15f,.15f,.38f,.15f); line(.62f,.15f,.85f,.15f); line(.85f,.15f,.85f,.38f); line(.15f,.62f,.15f,.85f); line(.15f,.85f,.38f,.85f); line(.62f,.85f,.85f,.85f); line(.85f,.85f,.85f,.62f) }
            "command" -> { line(.22f,.28f,.45f,.5f); line(.45f,.5f,.22f,.72f); line(.55f,.72f,.8f,.72f) }
            else -> circle(.5f,.5f,.3f)
        }
    }
}

private fun entryFileForLanguage(language: String): String = when (language) {
    "C17" -> "main.c"
    "C++20" -> "main.cpp"
    "Python" -> "main.py"
    "JavaScript" -> "index.js"
    "TypeScript" -> "index.ts"
    "Rust" -> "main.rs"
    "Java" -> "Main.java"
    "Kotlin" -> "Main.kt"
    "HTML/CSS" -> "index.html"
    "Shell" -> "main.sh"
    "Markdown" -> "README.md"
    else -> "README.md"
}

private fun sampleCode(file: String): String {
    val ext = file.substringAfterLast(".", "").lowercase()
    return when (ext) {
        "cpp", "cc", "cxx" -> "#include <iostream>\n\nint main() {\n    std::cout << \"Hello, C++20!\n\";\n    return 0;\n}"
        "h", "hpp", "hh", "hxx" -> "#pragma once\n\nvoid greet();"
        "py" -> "def main():\n    print(\"Hello, Python!\")\n\nif __name__ == \"__main__\":\n    main()\n"
        "js", "mjs" -> "function main() {\n  console.log(\"Hello, JavaScript!\");\n}\n\nmain();\n"
        "ts", "tsx" -> "function main(): void {\n  console.log(\"Hello, TypeScript!\");\n}\n\nmain();\n"
        "html", "htm" -> "<!doctype html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>New page</title>\n</head>\n<body>\n  <h1>Hello, world!</h1>\n</body>\n</html>\n"
        "css" -> "/* Styles */\n:root {\n  color-scheme: dark;\n}\n"
        "json" -> "{\n  \"name\": \"new-project\",\n  \"version\": \"1.0.0\"\n}\n"
        "sh", "bash" -> "#!/usr/bin/env bash\nset -euo pipefail\n\necho \"Hello, world!\"\n"
        "rs" -> "fn main() {\n    println!(\"Hello, Rust!\");\n}\n"
        "java" -> "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, Java!\");\n    }\n}\n"
        "kt", "kts" -> "fun main() {\n    println(\"Hello, Kotlin!\")\n}\n"
        "md" -> "# Notes\n\nStart writing here.\n"
        "txt", "log" -> ""
        "yaml", "yml" -> "name: new-project\nversion: 1.0.0\n"
        "toml" -> "name = \"new-project\"\nversion = \"1.0.0\"\n"
        "xml" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root />\n"
        else -> ""
    }
}