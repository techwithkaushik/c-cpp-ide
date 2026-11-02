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
import androidx.compose.material3.OutlinedTextField
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
    var terminalFontSize by remember { mutableStateOf(prefs.getInt(SettingsPreference.TERMINAL_FONT_SIZE, IdeDesignTokens.Metrics.DEFAULT_TERMINAL_FONT_SIZE).toFloat()) }
    var scrollbackLines by remember { mutableStateOf(prefs.getInt("terminal_scrollback_lines", 5000).toFloat()) }
    var autoKeyboard by remember { mutableStateOf(prefs.getBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, true)) }
    var connectionMode by remember { mutableStateOf(prefs.getString("terminal_connection_mode", "local") ?: "local") }
    var sshHost by remember { mutableStateOf(prefs.getString("terminal_ssh_host", "") ?: "") }
    var sshPort by remember { mutableStateOf(prefs.getInt("terminal_ssh_port", 22).toString()) }
    var sshUser by remember { mutableStateOf(prefs.getString("terminal_ssh_user", "") ?: "") }
    val categories = listOf("Interface", "Editor", "Terminal", "Runtime & Bootstrap")

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
            if (category != "Runtime & Bootstrap") TextButton(onClick = {
                when (category) {
                    "Interface" -> { onUiFontScaleChange(1.0f); prefs.edit().putFloat(SettingsPreference.UI_FONT_SCALE, 1.0f).apply() }
                    "Editor" -> { onEditorFontSizeChange(14f); onEditorWordWrapChange(false); onEditorThemeChange("Dark+ (VS Code)"); prefs.edit().putFloat(SettingsPreference.EDITOR_FONT_SIZE, 14f).putBoolean(SettingsPreference.EDITOR_WORD_WRAP, false).putString(SettingsPreference.EDITOR_THEME, "Dark+ (VS Code)").apply() }
                    "Terminal" -> { terminalFontSize = 13f; autoKeyboard = true; prefs.edit().putInt(SettingsPreference.TERMINAL_FONT_SIZE, 13).putBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, true).apply() }
                }
            }) { Text("↺ Reset section", color = SettingsAccent) }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)
        ) {
                when (category) {
                    "Runtime & Bootstrap" -> {
                        SettingsHeading("RUNTIME & BOOTSTRAP")
                        RuntimeSetupPanel(
                            onRuntimeReady = onRuntimeReady,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    "Interface" -> {
                        SettingsHeading("INTERFACE")
                        SettingsCard("Text size", "Interface", "${(uiFontScale * 100).toInt()}%")
                        Slider(value = uiFontScale, onValueChange = { onUiFontScaleChange(it); prefs.edit().putFloat(SettingsPreference.UI_FONT_SCALE, it).apply() }, valueRange = 0.85f..1.25f, steps = 7)
                    }
                    "Editor" -> {
                        SettingsHeading("EDITOR")
                        SettingsCard("Editor font size", "Current size", "${editorFontSize.toInt()} sp")
                        Slider(value = editorFontSize, onValueChange = { onEditorFontSizeChange(it); prefs.edit().putFloat(SettingsPreference.EDITOR_FONT_SIZE, it).apply() }, valueRange = 10f..24f)
                        SettingsSwitch("Word wrap", "Wrap long lines in the editor", editorWordWrap) {
                            onEditorWordWrapChange(it); prefs.edit().putBoolean(SettingsPreference.EDITOR_WORD_WRAP, it).apply()
                        }
                        SettingsHeading("COLOR THEME")
                        listOf("Dark+ (VS Code)", "Light+ (VS Code)", "Monokai", "Solarized Dark").forEach { theme ->
                            SettingsChoice(theme, editorTheme == theme) {
                                onEditorThemeChange(theme); prefs.edit().putString(SettingsPreference.EDITOR_THEME, theme).apply()
                            }
                        }
                    }
                    "Terminal" -> {
                        SettingsHeading("TERMINAL")
                        SettingsCard("Terminal font size", "Current size", "${terminalFontSize.toInt()} sp")
                        Slider(value = terminalFontSize, onValueChange = {
                            terminalFontSize = it
                            prefs.edit().putInt(SettingsPreference.TERMINAL_FONT_SIZE, it.toInt()).apply()
                        }, valueRange = 10f..22f, steps = 11)
                        SettingsCard("Scrollback lines", "Terminal history", scrollbackLines.toInt().toString())
                        Slider(value = scrollbackLines, onValueChange = { scrollbackLines = it; prefs.edit().putInt("terminal_scrollback_lines", it.toInt()).apply() }, valueRange = 1000f..10000f, steps = 8)
                        SettingsSwitch("Open keyboard automatically", "Show the soft keyboard when the terminal opens", autoKeyboard) {
                            autoKeyboard = it; prefs.edit().putBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, it).apply()
                        }
                        SettingsHeading("CONNECTION")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (connectionMode == "local") Button(onClick = { connectionMode = "local"; prefs.edit().putString("terminal_connection_mode", "local").apply() }, modifier = Modifier.weight(1f)) { Text("Local Ubuntu") }
                            else OutlinedButton(onClick = { connectionMode = "local"; prefs.edit().putString("terminal_connection_mode", "local").apply() }, modifier = Modifier.weight(1f)) { Text("Local Ubuntu") }
                            if (connectionMode == "ssh") Button(onClick = { connectionMode = "ssh"; prefs.edit().putString("terminal_connection_mode", "ssh").apply() }, modifier = Modifier.weight(1f)) { Text("SSH") }
                            else OutlinedButton(onClick = { connectionMode = "ssh"; prefs.edit().putString("terminal_connection_mode", "ssh").apply() }, modifier = Modifier.weight(1f)) { Text("SSH") }
                        }
                        if (connectionMode == "ssh") {
                            OutlinedTextField(value = sshHost, onValueChange = { sshHost = it; prefs.edit().putString("terminal_ssh_host", it).apply() }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Host / IP") })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = sshUser, onValueChange = { sshUser = it; prefs.edit().putString("terminal_ssh_user", it).apply() }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Username") })
                                OutlinedTextField(value = sshPort, onValueChange = { value -> if (value.all { it.isDigit() } && value.length <= 5) { sshPort = value; value.toIntOrNull()?.takeIf { it in 1..65535 }?.let { prefs.edit().putInt("terminal_ssh_port", it).apply() } } }, modifier = Modifier.weight(0.6f), singleLine = true, label = { Text("Port") })
                            }
                            Text("SSH starts in the terminal; authenticate there with your key or password.", color = SettingsMuted, fontSize = 11.sp)
                        }
                        Button(onClick = { category = "Runtime & Bootstrap" }, modifier = Modifier.fillMaxWidth()) { Text("Runtime & Bootstrap") }
                    }
                }
                Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Text(title, color = SettingsAccent, fontSize = 11.sp)
}

@Composable
private fun SettingsCard(title: String, eyebrow: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(IdeDesignTokens.Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = SettingsText, fontSize = IdeDesignTokens.Typography.body, modifier = Modifier.weight(1f))
            if (detail.endsWith("%") || detail.endsWith(" sp")) {
                Text(detail, color = SettingsMuted, fontSize = IdeDesignTokens.Typography.body)
            }
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(IdeDesignTokens.Spacing.md)) {
        Row(Modifier.fillMaxWidth().padding(IdeDesignTokens.Spacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
            Text(title, color = SettingsText, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
