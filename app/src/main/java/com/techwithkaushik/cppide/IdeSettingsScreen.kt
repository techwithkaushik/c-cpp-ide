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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.techwithkaushik.cppide.feature.terminal.TerminalConnectionConfig

private val SettingsBg = IdeDesignTokens.Color.Background
private val SettingsCard = IdeDesignTokens.Color.Surface
private val SettingsAccent = IdeDesignTokens.Color.Accent
private val SettingsMuted = IdeDesignTokens.Color.TextSecondary
private val SettingsText = IdeDesignTokens.Color.TextPrimary
private val SettingsPreference = IdeDesignTokens.Preference
private val DefaultTerminalExtraKeys = setOf("ESC", "TAB", "CTRL", "ALT", "HOME", "UP", "END", "PGUP", "LEFT", "DOWN", "RIGHT", "PGDN", "-", "/", "|", "~")

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
    var extraKeys by remember { mutableStateOf(prefs.getStringSet("terminal_extra_keys", DefaultTerminalExtraKeys)?.toSet() ?: DefaultTerminalExtraKeys) }
    var terminalConnectionMode by remember { mutableStateOf(prefs.getString("terminal_connection_mode", "ubuntu") ?: "ubuntu") }
    var sshHost by remember { mutableStateOf(prefs.getString("terminal_ssh_host", "").orEmpty()) }
    var sshPort by remember { mutableStateOf(prefs.getInt("terminal_ssh_port", 22).toString()) }
    var sshUser by remember { mutableStateOf(prefs.getString("terminal_ssh_user", "").orEmpty()) }
    var sshPassword by remember { mutableStateOf(TerminalConnectionConfig.sshPassword) }
    var sshKeyLabel by remember { mutableStateOf(if (TerminalConnectionConfig.sshPrivateKey != null) "Private key loaded in memory" else "No private key selected") }
    var sshKeyPassphrase by remember { mutableStateOf(TerminalConnectionConfig.sshPrivateKeyPassphrase) }
    var sshKeyError by remember { mutableStateOf<String?>(null) }
    val sshPrivateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 128 * 1024) { "Private key file must be at most 128 KiB." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Could not read the selected private key.")
                require(bytes.isNotEmpty()) { "The selected private key is empty." }
                TerminalConnectionConfig.sshPrivateKey = bytes
                sshKeyLabel = "Private key loaded in memory"
                sshKeyError = null
            } catch (error: Exception) {
                sshKeyError = error.message ?: "Could not load private key."
            }
        }
    }
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
                    "Terminal" -> { terminalFontSize = 13f; scrollbackLines = 5000f; autoKeyboard = true; terminalConnectionMode = "ubuntu"; extraKeys = DefaultTerminalExtraKeys; prefs.edit().putInt(SettingsPreference.TERMINAL_FONT_SIZE, 13).putInt("terminal_scrollback_lines", 5000).putBoolean(SettingsPreference.TERMINAL_AUTO_KEYBOARD, true).putString("terminal_connection_mode", "ubuntu").putStringSet("terminal_extra_keys", DefaultTerminalExtraKeys).apply() }
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
                        SettingsHeading("SESSION CONNECTION")
                        Text("Choose the shell that opens with the Terminal panel. Configure connection details here; long-press a session tab to rename or close it.", color = SettingsMuted, fontSize = 11.sp)
                        SettingsChoice("Ubuntu · Local shell", terminalConnectionMode == "ubuntu") {
                            terminalConnectionMode = "ubuntu"
                            prefs.edit().putString("terminal_connection_mode", "ubuntu").apply()
                        }
                        SettingsChoice("SSH · Remote shell", terminalConnectionMode == "ssh") {
                            terminalConnectionMode = "ssh"
                            prefs.edit().putString("terminal_connection_mode", "ssh").apply()
                        }
                        Text("SSH connection", color = SettingsText, fontSize = 13.sp)
                        OutlinedTextField(
                            value = sshHost,
                            onValueChange = { sshHost = it; prefs.edit().putString("terminal_ssh_host", it).apply() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Host / IP") },
                            singleLine = true
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = sshPort,
                                onValueChange = { sshPort = it.filter(Char::isDigit).take(5); prefs.edit().putInt("terminal_ssh_port", sshPort.toIntOrNull() ?: 22).apply() },
                                modifier = Modifier.weight(0.35f),
                                label = { Text("Port") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = sshUser,
                                onValueChange = { sshUser = it; prefs.edit().putString("terminal_ssh_user", it).apply() },
                                modifier = Modifier.weight(0.65f),
                                label = { Text("Username") },
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = sshPassword,
                            onValueChange = { sshPassword = it; TerminalConnectionConfig.sshPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("SSH password (optional if using a key)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true
                        )
                        OutlinedButton(
                            onClick = { sshPrivateKeyPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (TerminalConnectionConfig.sshPrivateKey == null) "Select private key" else "Replace private key") }
                        Text(sshKeyLabel, color = SettingsMuted, fontSize = 10.sp)
                        if (TerminalConnectionConfig.sshPrivateKey != null) {
                            OutlinedTextField(
                                value = sshKeyPassphrase,
                                onValueChange = { sshKeyPassphrase = it; TerminalConnectionConfig.sshPrivateKeyPassphrase = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Private key passphrase (optional)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            TextButton(onClick = {
                                TerminalConnectionConfig.sshPrivateKey = null
                                TerminalConnectionConfig.sshPrivateKeyPassphrase = ""
                                sshKeyPassphrase = ""
                                sshKeyLabel = "No private key selected"
                            }) { Text("Clear private key", color = SettingsAccent) }
                        }
                        sshKeyError?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 10.sp) }
                        Text("Password, private key and passphrase remain in memory only; they are never written to preferences or disk.", color = SettingsMuted, fontSize = 10.sp)
                        SettingsHeading("EXTRA KEYS · TERMUX STYLE")
                        Text("Choose which shortcut keys appear above the terminal. Changes are saved automatically.", color = SettingsMuted, fontSize = 11.sp)
                        listOf("ESC", "TAB", "CTRL", "ALT", "HOME", "UP", "END", "PGUP", "LEFT", "DOWN", "RIGHT", "PGDN", "-", "/", "|", "~").forEach { key ->
                            SettingsSwitch(
                                when (key) { "PGUP" -> "Page Up"; "PGDN" -> "Page Down"; "-" -> "Hyphen"; "/" -> "Slash"; "|" -> "Pipe"; "~" -> "Tilde"; else -> key },
                                "Show $key in the terminal extra-keys bar",
                                key in extraKeys
                            ) { enabled ->
                                extraKeys = if (enabled) extraKeys + key else extraKeys - key
                                prefs.edit().putStringSet("terminal_extra_keys", extraKeys).apply()
                            }
                        }
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
                                        Button(
                            onClick = { category = "Runtime & Bootstrap" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Runtime setup")
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = SettingsAccent, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsCard(title: String, eyebrow: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = SettingsCard), shape = RoundedCornerShape(IdeDesignTokens.Spacing.md)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
