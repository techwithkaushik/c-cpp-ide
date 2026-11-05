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
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import com.techwithkaushik.cppide.feature.terminal.RuntimeSetupPanel

private val SettingsBg = IdeDesignTokens.Color.Background
private val SettingsCard = IdeDesignTokens.Color.Surface
private val SettingsAccent = IdeDesignTokens.Color.Accent
private val SettingsMuted = IdeDesignTokens.Color.TextSecondary
private val SettingsText = IdeDesignTokens.Color.TextPrimary
private val SettingsPreference = IdeDesignTokens.Preference

@Composable
internal fun IdeSettingsScreen(
    editorFontSize: Float,
    onEditorFontSizeChange: (Float) -> Unit,
    editorWordWrap: Boolean,
    onEditorWordWrapChange: (Boolean) -> Unit,
    editorTheme: String,
    onEditorThemeChange: (String) -> Unit,
    projectName: String,
    uiFontScale: Float,
    onUiFontScaleChange: (Float) -> Unit,
    onBack: () -> Unit,
    onRuntimeReady: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences(SettingsPreference.FILE, Context.MODE_PRIVATE) }
    var category by rememberSaveable { mutableStateOf("Interface") }
    var terminalFontSize by remember { mutableStateOf(prefs.getInt(SettingsPreference.TERMINAL_FONT_SIZE, IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE).coerceIn(IdeDesignTokens.Metrics.MIN_TERMINAL_FONT_SIZE, IdeDesignTokens.Metrics.MAX_TERMINAL_FONT_SIZE).toFloat()) }
    var autoKeyboard by remember { mutableStateOf(prefs.getBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, true)) }
    var cursorBlink by remember { mutableStateOf(prefs.getBoolean(SettingsPreference.TERMINAL_CURSOR_BLINK, true)) }
    var resetMessage by rememberSaveable { mutableStateOf("") }
    val categories = listOf("Interface", "Editor", "Terminal", "Runtime & Bootstrap", "Languages", "Build & Run", "Keyboard", "About")

    Column(Modifier.fillMaxSize().background(SettingsBg)) {
        Row(
            Modifier.fillMaxWidth().background(SettingsCard).padding(horizontal = IdeDesignTokens.Spacing.lg, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
        ) {
            TextButton(onClick = onBack) { Text("‹ Back", color = SettingsAccent) }
            Column(Modifier.weight(1f)) {
                Text("Settings", color = SettingsText, fontSize = 22.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = IdeDesignTokens.Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            categories.forEach { item ->
                if (category == item) Button(onClick = { category = item }) { Text(item, fontSize = 11.sp) }
                else OutlinedButton(onClick = { category = item }) { Text(item, fontSize = 11.sp) }
            }
        }
        Divider(color = IdeDesignTokens.Color.Divider)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(category, color = SettingsAccent, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                when (category) {
                    "Interface" -> { onUiFontScaleChange(1.0f); prefs.edit().putFloat(SettingsPreference.UI_FONT_SCALE, 1.0f).apply() }
                    "Editor" -> { onEditorFontSizeChange(14f); onEditorWordWrapChange(false); onEditorThemeChange("Dark+ (VS Code)"); prefs.edit().putFloat(SettingsPreference.EDITOR_FONT_SIZE, 14f).putBoolean(SettingsPreference.EDITOR_WORD_WRAP, false).putString(SettingsPreference.EDITOR_THEME, "Dark+ (VS Code)").apply() }
                    "Terminal" -> { terminalFontSize = IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE.toFloat(); autoKeyboard = true; cursorBlink = true; prefs.edit().putInt(SettingsPreference.TERMINAL_FONT_SIZE, IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE).putBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, true).putBoolean(SettingsPreference.TERMINAL_CURSOR_BLINK, true).apply() }
                    else -> resetMessage = "This section has no saved preferences to reset yet."
                }
                if (category == "Interface" || category == "Editor" || category == "Terminal") resetMessage = category + " reset."
            }) { Text("↺ Reset section", color = SettingsAccent) }
        }
        if (resetMessage.isNotBlank()) Text(resetMessage, color = SettingsMuted, modifier = Modifier.padding(horizontal = 14.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
        ) {
                when (category) {
                    "Runtime & Bootstrap" -> {
                        SettingsHeading("RUNTIME & BOOTSTRAP", "Install terminal runtime.")
                        RuntimeSetupPanel(
                            message = "Choose the bootstrap ZIP for this app and device ABI. Files extract directly into the app-private usr/ prefix.",
                            onRuntimeReady = onRuntimeReady,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "Interface" -> {
                        SettingsHeading("INTERFACE", "Adjust text size.")
                        SettingsCard("Text size", "Interface", "${(uiFontScale * 100).toInt()}%")
                        Slider(value = uiFontScale, onValueChange = { onUiFontScaleChange(it); prefs.edit().putFloat(SettingsPreference.UI_FONT_SCALE, it).apply() }, valueRange = 0.85f..1.25f, steps = 7)
                    }
                    "Editor" -> {
                        SettingsHeading("EDITOR", "Text and appearance.")
                        SettingsCard("Editor font size", "Current size", "${editorFontSize.toInt()} sp")
                        Slider(value = editorFontSize, onValueChange = { onEditorFontSizeChange(it); prefs.edit().putFloat(SettingsPreference.EDITOR_FONT_SIZE, it).apply() }, valueRange = 10f..24f)
                        SettingsSwitch("Word wrap", "Wrap long lines in the editor", editorWordWrap) {
                            onEditorWordWrapChange(it); prefs.edit().putBoolean(SettingsPreference.EDITOR_WORD_WRAP, it).apply()
                        }
                        SettingsHeading("COLOR THEME", "Choose a theme.")
                        listOf("Dark+ (VS Code)", "Light+ (VS Code)", "Monokai", "Solarized Dark").forEach { theme ->
                            SettingsChoice(theme, editorTheme == theme) {
                                onEditorThemeChange(theme); prefs.edit().putString(SettingsPreference.EDITOR_THEME, theme).apply()
                            }
                        }
                        SettingsCard("Editor engine", "Implementation", "Sora code editor")
                    }
                    "Terminal" -> {
                        SettingsHeading("TERMINAL", "Changes apply to the running terminal immediately.")
                        SettingsCard("Terminal font size", "Current size", "${terminalFontSize.toInt()} sp")
                        Slider(value = terminalFontSize, onValueChange = {
                            terminalFontSize = it
                            prefs.edit().putInt(SettingsPreference.TERMINAL_FONT_SIZE, it.toInt()).apply()
                        }, valueRange = IdeDesignTokens.Metrics.MIN_TERMINAL_FONT_SIZE.toFloat()..IdeDesignTokens.Metrics.MAX_TERMINAL_FONT_SIZE.toFloat(), steps = IdeDesignTokens.Metrics.MAX_TERMINAL_FONT_SIZE - IdeDesignTokens.Metrics.MIN_TERMINAL_FONT_SIZE - 1)
                        SettingsSwitch("Open keyboard automatically", "Show the soft keyboard when a terminal session opens", autoKeyboard) {
                            autoKeyboard = it; prefs.edit().putBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, it).apply()
                        }
                        SettingsSwitch("Blink terminal cursor", "Animate the cursor while the terminal is active", cursorBlink) {
                            cursorBlink = it; prefs.edit().putBoolean(SettingsPreference.TERMINAL_CURSOR_BLINK, it).apply()
                        }
                        SettingsCard("Main terminal", "Runtime status", "The terminal requires an installed ABI-matched bootstrap and a passing shell execution test.")
                        Button(
                            onClick = { category = "Runtime & Bootstrap" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set up main terminal")
                        }
                        SettingsCard("Input", "Shell session", "Commands run inside the app-private Linux environment, not Android's fallback shell.")
                        SettingsCard("Clipboard", "Copy and paste", "Terminal copy/paste uses the Android clipboard")
                    }
                    "Languages" -> {
                        SettingsHeading("LANGUAGES & TOOLS", "Select a language and inspect its installed tools and configuration.")
                        LanguageSettingsPanel(projectName = projectName)
                    }
                    "Build & Run" -> {
                        SettingsHeading("BUILD & RUN", "Build options.")
                        SettingsCard("Target environment", "Linux", "Embedded app-private runtime; no external Termux app is required")
                        SettingsCard("Bootstrap architecture", "Current APK", "ABI-matched bootstrap is required; ARM32 APK uses armeabi-v7a")
                        SettingsCard("Compiler", "C / C++", "Compiler availability depends on the selected bootstrap and installed packages")
                        SettingsCard("Run output", "Output panel", "Program output appears in the IDE output panel when the runner is connected")
                    }
                    "Keyboard" -> {
                        SettingsHeading("KEYBOARD & SHORTCUTS", "Common shortcuts.")
                        SettingsCard("Terminal", "Show keyboard", if (autoKeyboard) "Automatically on terminal open" else "Tap terminal to show keyboard")
                        SettingsCard("Common actions", "Editor", "Ctrl+S Save · Ctrl+F Find · Ctrl+Z Undo · Ctrl+Shift+P Command Palette")
                        SettingsCard("Terminal controls", "Interactive", "Enter runs commands; Ctrl+C interrupts foreground processes when supported")
                    }
                    else -> {
                        SettingsHeading("ABOUT", "App information.")
                        SettingsCard("Application", "Code Studio", "Android-native coding workspace")
                        SettingsCard("Architecture", "Current device APK", "Bootstrap ABI must match this device and APK")
                        SettingsCard("Privacy", "Runtime files", "Runtime and projects are stored in app-private storage")
                        SettingsCard("Troubleshooting", "Terminal", "Open Runtime & Bootstrap and install the matching ZIP.")
                    }
                }
                Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = SettingsAccent, fontSize = 11.sp)
        Text(subtitle, color = SettingsMuted, fontSize = IdeDesignTokens.Typography.caption)
    }
}

@Composable
private fun SettingsCard(title: String, eyebrow: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(IdeDesignTokens.Spacing.md)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(eyebrow.uppercase(), color = SettingsAccent, fontSize = 9.sp)
            Text(title, color = SettingsText, fontSize = IdeDesignTokens.Typography.body)
            Text(detail, color = SettingsMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(IdeDesignTokens.Spacing.md)) {
        Row(Modifier.fillMaxWidth().padding(IdeDesignTokens.Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.xs)) {
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
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = IdeDesignTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(IdeDesignTokens.Spacing.md).background(if (selected) SettingsAccent else SettingsMuted, RoundedCornerShape(50)))
        Text(title, color = SettingsText, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (selected) Text("✓", color = SettingsAccent)
    }
}
