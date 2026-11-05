package com.techwithkaushik.cppide

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LCard = IdeDesignTokens.Color.Surface
private val LText = IdeDesignTokens.Color.TextPrimary
private val LMuted = IdeDesignTokens.Color.TextSecondary
private val LAccent = IdeDesignTokens.Color.Accent

@Composable
internal fun LanguageSettingsPanel(projectName: String) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val inspector = remember(context) { CcppToolchainInspector(context) }
    val prefs = remember(context) { context.getSharedPreferences("language_settings_c_cpp", 0) }
    val languages = remember { LanguageToolingCatalog.all() }
    var selected by rememberSaveable { mutableStateOf("c-cpp") }
    var menu by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<CppToolchainReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val paths = remember { mutableStateMapOf<String, String>() }
    val language = languages.firstOrNull { it.id == selected } ?: languages.first()

    LaunchedEffect(selected, refresh, projectName) {
        report = null
        error = null
        if (selected != "c-cpp") { busy = false; return@LaunchedEffect }
        busy = true
        try {
            val value = withContext(Dispatchers.IO) { inspector.inspect(projectName) }
            report = value
            value.tools.forEach { paths[it.executable] = it.path.orEmpty() }
        } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        finally { busy = false }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LANGUAGE", color = LAccent, fontSize = 11.sp)
        Text("Select a language. Settings appear only after its tools are found and verified.", color = LMuted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { menu = true }) { Text("${language.displayName}  ▾") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                languages.forEach { item -> DropdownMenuItem(
                    text = { Text(item.displayName) },
                    onClick = { selected = item.id; menu = false }
                ) }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(enabled = selected == "c-cpp" && !busy, onClick = { refresh++ }) { Text("↻ Refresh") }
        }
        if (selected != "c-cpp") {
            LNotice("${language.displayName} settings are not available yet", "C/C++ is the first language being integrated. Other languages will appear after their own toolchain and configuration discovery is implemented.")
            return@Column
        }
        if (busy) LNotice("Checking C/C++", "Verifying runtime, executable paths and tool versions…")
        error?.let { LNotice("Inspection failed", it) }
        val current = report ?: return@Column
        if (!current.runtimeReady) {
            LNotice("Runtime unavailable", current.runtimeMessage)
            Text("Install or repair the embedded runtime in Settings → Runtime & Bootstrap, then refresh.", color = LMuted, fontSize = 12.sp)
            return@Column
        }
        val missing = current.tools.filter { it.executable in CppToolchainReport.REQUIRED_TOOLS && !it.installed }
        if (missing.isNotEmpty()) {
            LNotice("C/C++ tools not detected", "Required tools missing: ${missing.joinToString { it.executable }}. Open the Ubuntu terminal and run the install command below, or provide absolute guest paths. Refresh after installation.")
            Text("Ubuntu package command: sudo apt update && sudo apt install clang clangd clang-format clang-tidy lldb", color = LText, fontSize = 12.sp)
            ToolPathFields(current.tools.filter { it.executable in CppToolchainReport.REQUIRED_TOOLS }, paths, { name, value -> paths[name] = value }) { name ->
                inspector.savePath(name, paths[name].orEmpty().ifBlank { null }); refresh++
            }
            return@Column
        }

        LCardView("C/C++ toolchain verified", "Compiler and clangd version checks succeeded in the embedded runtime.")
        Text("INSTALLED TOOLS", color = LAccent, fontSize = 11.sp)
        current.tools.forEach { tool ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tool.executable, color = LText, fontSize = 13.sp)
                    Text(tool.path.orEmpty(), color = LMuted, fontSize = 10.sp)
                    tool.version?.let { Text(it, color = LMuted, fontSize = 10.sp) }
                }
                Text(if (tool.installed) "Ready" else "Missing", color = if (tool.installed) Color(0xFF7DDBB0) else Color(0xFFE5B978), fontSize = 11.sp)
            }
        }

        Text("C / C++ LANGUAGE SETTINGS", color = LAccent, fontSize = 11.sp)
        var cStd by remember { mutableStateOf(prefs.getString("c_standard", "c17") ?: "c17") }
        var cppStd by remember { mutableStateOf(prefs.getString("cpp_standard", "c++20") ?: "c++20") }
        var compilerArgs by remember { mutableStateOf(prefs.getString("compiler_args", "") ?: "") }
        var clangdArgs by remember { mutableStateOf(prefs.getString("clangd_args", "") ?: "") }
        var includes by remember { mutableStateOf(prefs.getString("include_paths", "") ?: "") }
        var defines by remember { mutableStateOf(prefs.getString("defines", "") ?: "") }
        LDropdown("C standard", cStd, listOf("c11", "c17", "c23")) { cStd = it; prefs.edit().putString("c_standard", it).apply() }
        LDropdown("C++ standard", cppStd, listOf("c++14", "c++17", "c++20", "c++23")) { cppStd = it; prefs.edit().putString("cpp_standard", it).apply() }
        OutlinedTextField(compilerArgs, { compilerArgs = it; prefs.edit().putString("compiler_args", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Compiler arguments") })
        OutlinedTextField(clangdArgs, { clangdArgs = it; prefs.edit().putString("clangd_args", it).apply() }, Modifier.fillMaxWidth(), label = { Text("clangd arguments") })
        OutlinedTextField(includes, { includes = it; prefs.edit().putString("include_paths", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Include directories") })
        OutlinedTextField(defines, { defines = it; prefs.edit().putString("defines", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Preprocessor definitions") })

        Text("WORKSPACE CONFIGURATION", color = LAccent, fontSize = 11.sp)
        LConfig(".clangd", current.workspaceFiles.clangdConfig)
        LConfig(".clang-format", current.workspaceFiles.clangFormat)
        LConfig("compile_commands.json", current.workspaceFiles.compileCommands)
        LConfig("Build files", current.workspaceFiles.buildSystemFiles.takeIf { it.isNotEmpty() }?.joinToString())
        if (current.workspaceFiles.compileCommands == null) Text("No compile_commands.json found. clangd can use fallback flags; include paths and defines may need manual configuration.", color = Color(0xFFE5B978), fontSize = 11.sp)

        Text("TOOL PATH OVERRIDES", color = LAccent, fontSize = 11.sp)
        ToolPathFields(current.tools, paths, { name, value -> paths[name] = value }) { name ->
            inspector.savePath(name, paths[name].orEmpty().ifBlank { null }); refresh++
        }
    }
}

@Composable
private fun ToolPathFields(tools: List<CppToolStatus>, paths: Map<String, String>, onChange: (String, String) -> Unit, onSave: (String) -> Unit) {
    tools.forEach { tool ->
        OutlinedTextField(paths[tool.executable].orEmpty(), { onChange(tool.executable, it) }, Modifier.fillMaxWidth(),
            label = { Text("${tool.executable} absolute path") }, placeholder = { Text("/usr/bin/${tool.executable}") }, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(tool.detail ?: if (tool.installed) "Verified" else "Not detected", color = LMuted, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onSave(tool.executable) }) { Text("Save path") }
        }
    }
}

@Composable
private fun LDropdown(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, color = LMuted, fontSize = 11.sp)
        OutlinedButton(onClick = { expanded = true }) { Text("$value  ▾") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onSelect(it); expanded = false }) }
        }
    }
}

@Composable
private fun LConfig(name: String, path: String?) {
    Text("$name: ${path ?: "Not found in current workspace"}", color = if (path == null) LMuted else Color(0xFF7DDBB0), fontSize = 11.sp)
}

@Composable
private fun LNotice(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = LCard)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = LText, fontSize = 14.sp)
            Text(detail, color = LMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LCardView(title: String, detail: String) = LNotice(title, detail)
