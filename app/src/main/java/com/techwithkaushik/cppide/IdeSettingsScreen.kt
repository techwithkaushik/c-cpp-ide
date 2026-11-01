package com.techwithkaushik.cppide

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.feature.terminal.RuntimeSetupPanel

private val SettingsBg = Color(0xFF101820)
private val SettingsCard = Color(0xFF192733)
private val SettingsAccent = Color(0xFF659AD2)
private val SettingsMuted = Color(0xFF9AA8BA)
private val SettingsText = Color(0xFFE8EDF5)

@Composable
internal fun IdeSettingsScreen(
    editorFontSize: Float,
    onEditorFontSizeChange: (Float) -> Unit,
    editorWordWrap: Boolean,
    onEditorWordWrapChange: (Boolean) -> Unit,
    editorTheme: String,
    onEditorThemeChange: (String) -> Unit,
    projectName: String,
    onBack: () -> Unit,
    onRuntimeReady: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("ide_settings", Context.MODE_PRIVATE) }
    var category by rememberSaveable { mutableStateOf("Overview") }
    var terminalFontSize by remember { mutableStateOf(prefs.getInt("terminal_font_size", 13).toFloat()) }
    var autoKeyboard by remember { mutableStateOf(prefs.getBoolean("terminal_auto_keyboard", true)) }
    var cursorBlink by remember { mutableStateOf(prefs.getBoolean("terminal_cursor_blink", true)) }
    var showLineNumbers by remember { mutableStateOf(prefs.getBoolean("editor_line_numbers", true)) }
    var smoothScroll by remember { mutableStateOf(prefs.getBoolean("editor_smooth_scroll", true)) }
    var haptics by remember { mutableStateOf(prefs.getBoolean("ui_haptics", false)) }
    val categories = listOf("Overview", "UI", "Editor", "Terminal", "Runtime & Bootstrap", "Languages", "Build & Run", "Keyboard", "About")

    Column(Modifier.fillMaxSize().background(SettingsBg)) {
        Row(
            Modifier.fillMaxWidth().background(SettingsCard).padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = SettingsAccent) }
            Column(Modifier.weight(1f)) {
                Text("Settings", color = SettingsText, fontSize = 20.sp)
                Text("Configure the entire coding environment", color = SettingsMuted, fontSize = 11.sp)
            }
            TextButton(onClick = { prefs.edit().clear().apply(); onBack() }) {
                Text("Reset", color = SettingsMuted)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            categories.forEach { item ->
                if (category == item) Button(onClick = { category = item }) { Text(item, fontSize = 11.sp) }
                else OutlinedButton(onClick = { category = item }) { Text(item, fontSize = 11.sp) }
            }
        }
        Divider(color = Color(0xFF243D52))
        if (category == "Runtime & Bootstrap") {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Text("RUNTIME & BOOTSTRAP", color = SettingsAccent, fontSize = 12.sp)
                Text("Import or replace the app-specific bootstrap. The existing Termux app is not modified.", color = SettingsMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                RuntimeSetupPanel(
                    message = "Select the bootstrap ZIP for this IDE and enter its published SHA-256 checksum.",
                    onRuntimeReady = onRuntimeReady,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (category) {
                    "Overview" -> {
                        SettingsHeading("IDE CONFIGURATION", "All preferences are grouped here. Changes to editor and terminal controls are saved on this device.")
                        SettingsCard("Workspace", "Current project", projectName)
                        SettingsCard("Editor", "Current theme", editorTheme)
                        SettingsCard("Terminal", "Embedded runtime", "App-private Termux-compatible Linux environment")
                        SettingsCard("Bootstrap", "Manual setup", "Open Runtime & Bootstrap to select or replace a bootstrap ZIP")
                        SettingsCard("Language support", "Primary focus", "C, C++, Python, shell, JavaScript/TypeScript, JSON, TOML, YAML, HTML and CSS")
                    }
                    "UI" -> {
                        SettingsHeading("USER INTERFACE", "Interface and workspace preferences.")
                        SettingsCard("Layout", "Workspace", "VS Code-style drawer, editor tabs, file explorer and draggable bottom tool panel")
                        SettingsSwitch("Haptic feedback", "Use vibration for supported UI actions", haptics) {
                            haptics = it; prefs.edit().putBoolean("ui_haptics", it).apply()
                        }
                        SettingsCard("Navigation", "Settings page", "This is a dedicated full-screen page. Use Back to return to the previous screen.")
                        SettingsCard("Color scheme", "Current app shell", "Dark IDE palette")
                    }
                    "Editor" -> {
                        SettingsHeading("EDITOR", "Tune reading and editing comfort.")
                        SettingsCard("Editor font size", "Current size", "${editorFontSize.toInt()} sp")
                        Slider(value = editorFontSize, onValueChange = { onEditorFontSizeChange(it); prefs.edit().putFloat("editor_font_size", it).apply() }, valueRange = 10f..24f)
                        SettingsSwitch("Word wrap", "Wrap long lines in the editor", editorWordWrap) {
                            onEditorWordWrapChange(it); prefs.edit().putBoolean("editor_word_wrap", it).apply()
                        }
                        SettingsSwitch("Line numbers", "Show line numbers where supported by the editor", showLineNumbers) {
                            showLineNumbers = it; prefs.edit().putBoolean("editor_line_numbers", it).apply()
                        }
                        SettingsSwitch("Smooth scrolling", "Prefer smoother editor navigation", smoothScroll) {
                            smoothScroll = it; prefs.edit().putBoolean("editor_smooth_scroll", it).apply()
                        }
                        SettingsHeading("EDITOR COLOR THEME", "Choose an editor palette.")
                        listOf("Dark+ (VS Code)", "Light+ (VS Code)", "Monokai", "Solarized Dark").forEach { theme ->
                            SettingsChoice(theme, editorTheme == theme) {
                                onEditorThemeChange(theme); prefs.edit().putString("editor_theme", theme).apply()
                            }
                        }
                        SettingsCard("Editor engine", "Implementation", "Sora code editor")
                    }
                    "Terminal" -> {
                        SettingsHeading("TERMINAL", "Keyboard, font and interactive shell preferences.")
                        SettingsCard("Terminal font size", "Current size", "${terminalFontSize.toInt()} sp")
                        Slider(value = terminalFontSize, onValueChange = {
                            terminalFontSize = it
                            prefs.edit().putInt("terminal_font_size", it.toInt()).apply()
                        }, valueRange = 10f..22f, steps = 11)
                        SettingsSwitch("Open keyboard automatically", "Show the soft keyboard when the terminal opens", autoKeyboard) {
                            autoKeyboard = it; prefs.edit().putBoolean("terminal_auto_keyboard", it).apply()
                        }
                        SettingsSwitch("Cursor blink", "Enable terminal cursor blinking", cursorBlink) {
                            cursorBlink = it; prefs.edit().putBoolean("terminal_cursor_blink", it).apply()
                        }
                        SettingsCard("Input", "Shell session", "Interactive PTY with command input and terminal output")
                        SettingsCard("Clipboard", "Copy and paste", "Terminal copy/paste uses the Android clipboard")
                    }
                    "Languages" -> {
                        SettingsHeading("LANGUAGES & TOOLS", "Language tooling paths are configurable per language.")
                        listOf("C / C++ — clangd, clang, clang++", "Python — pyright, python", "JavaScript / TypeScript — typescript-language-server, prettier", "Lua — lua-language-server", "Rust — rust-analyzer, rustc", "Text, JSON, TOML, YAML, HTML and CSS").forEach {
                            SettingsCard(it.substringBefore(" — "), "Tooling", it.substringAfter(" — ", it))
                        }
                        SettingsCard("Custom executable paths", "Advanced", "Use the Languages tab in the existing tooling configuration to save and validate custom paths.")
                    }
                    "Build & Run" -> {
                        SettingsHeading("BUILD & RUN", "Build and execution settings.")
                        SettingsCard("Target environment", "Linux", "Embedded app-private runtime; no external Termux app is required")
                        SettingsCard("Bootstrap architecture", "Current APK", "ABI-matched bootstrap is required; ARM32 APK uses armeabi-v7a")
                        SettingsCard("Compiler", "C / C++", "Compiler availability depends on the selected bootstrap and installed packages")
                        SettingsCard("Run output", "Output panel", "Program output appears in the IDE output panel when the runner is connected")
                    }
                    "Keyboard" -> {
                        SettingsHeading("KEYBOARD & SHORTCUTS", "Terminal keyboard behavior.")
                        SettingsCard("Terminal", "Show keyboard", if (autoKeyboard) "Automatically on terminal open" else "Tap terminal to show keyboard")
                        SettingsCard("Common actions", "Editor", "Ctrl+S Save · Ctrl+F Find · Ctrl+Z Undo · Ctrl+Shift+P Command Palette")
                        SettingsCard("Terminal controls", "Interactive", "Enter runs commands; Ctrl+C interrupts foreground processes when supported")
                    }
                    else -> {
                        SettingsHeading("ABOUT CODE STUDIO", "IDE version and runtime notes.")
                        SettingsCard("Application", "Code Studio", "Android-native coding workspace")
                        SettingsCard("Architecture", "Current device APK", "Bootstrap ABI must match this device and APK")
                        SettingsCard("Privacy", "Runtime files", "Runtime and projects are stored in app-private storage")
                        SettingsCard("Troubleshooting", "Terminal won't open", "Open Runtime & Bootstrap, select the correct ZIP, and verify the SHA-256 shown by CI.")
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun SettingsHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = SettingsAccent, fontSize = 11.sp)
        Text(subtitle, color = SettingsMuted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsCard(title: String, eyebrow: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(eyebrow.uppercase(), color = SettingsAccent, fontSize = 9.sp)
            Text(title, color = SettingsText, fontSize = 14.sp)
            Text(detail, color = SettingsMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = SettingsText, fontSize = 13.sp)
                Text(description, color = SettingsMuted, fontSize = 11.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SettingsChoice(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Color(0xFF243D52) else SettingsCard, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).background(if (selected) SettingsAccent else SettingsMuted, RoundedCornerShape(50)))
        Text(title, color = SettingsText, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (selected) Text("✓", color = SettingsAccent)
    }
}
