package com.techwithkaushik.cppide

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DeveloperOptionsPanel() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var enabled by remember { mutableStateOf(DeveloperLog.isEnabled(context)) }
    var exporting by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var fileCount by remember { mutableStateOf(DeveloperLog.fileCount(context)) }
    var totalBytes by remember { mutableStateOf(DeveloperLog.totalBytes(context)) }

    fun refreshLogs(openViewer: Boolean = false) {
        scope.launch {
            logText = withContext(Dispatchers.IO) { DeveloperLog.read(context) }
            fileCount = DeveloperLog.fileCount(context)
            totalBytes = DeveloperLog.totalBytes(context)
            if (openViewer) showLogs = true
        }
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            exporting = true
            scope.launch {
                val result = runCatching { DeveloperLog.export(context, uri) }
                exporting = false
                if (result.isSuccess) {
                    DeveloperLog.info(context, "DeveloperLog", "Log archive exported")
                    Toast.makeText(context, "Developer logs exported", Toast.LENGTH_LONG).show()
                } else {
                    DeveloperLog.error(context, "DeveloperLog", "Log archive export failed", result.exceptionOrNull())
                    Toast.makeText(context, "Could not export logs", Toast.LENGTH_LONG).show()
                }
                refreshLogs()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
        Card(colors = CardDefaults.cardColors(containerColor = IdeDesignTokens.Color.Surface)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text("Developer logging", color = IdeDesignTokens.Color.TextPrimary, fontSize = 13.sp)
                    Text("Capture app, Ubuntu, PRoot, terminal, SSH and crash diagnostics. Logging is off by default.",
                        color = IdeDesignTokens.Color.TextSecondary, fontSize = 11.sp)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    DeveloperLog.setEnabled(context, it)
                    fileCount = DeveloperLog.fileCount(context)
                    totalBytes = DeveloperLog.totalBytes(context)
                })
            }
        }
        Text(if (enabled) "Logging is ON. New diagnostics are saved automatically in private app storage."
            else "Logging is OFF. Existing log files are kept until you clear or export them.",
            color = IdeDesignTokens.Color.TextSecondary, fontSize = 11.sp)
        Text("Stored logs: $fileCount files · ${"%.1f".format(Locale.US, totalBytes / 1024.0)} KB · maximum 4 files of 512 KB each",
            color = IdeDesignTokens.Color.TextSecondary, fontSize = 11.sp)
        Text("Passwords, tokens, authorization values and private keys are redacted. Terminal commands and terminal output are not collected. Android does not let ordinary apps read the complete system Logcat; only app-instrumented events and uncaught app-process crashes are captured.",
            color = IdeDesignTokens.Color.TextSecondary, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
            OutlinedButton(enabled = DeveloperLog.hasLogs(context), onClick = { refreshLogs(true) }, modifier = Modifier.weight(1f)) { Text("View logs") }
            OutlinedButton(enabled = DeveloperLog.hasLogs(context) && !exporting, onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportPicker.launch("code-studio-logs-$stamp.zip")
            }, modifier = Modifier.weight(1f)) { Text(if (exporting) "Exporting…" else "Export ZIP") }
        }
        OutlinedButton(enabled = DeveloperLog.hasLogs(context), onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Clear saved logs") }
    }

    if (showLogs) AlertDialog(
        onDismissRequest = { showLogs = false },
        title = { Text("Saved developer logs") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(logText.ifBlank { "No diagnostic events have been saved yet." },
                        fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = IdeDesignTokens.Color.TextPrimary)
                }
            }
        },
        confirmButton = { TextButton(onClick = { showLogs = false }) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = { refreshLogs() }) { Text("Refresh") }
                TextButton(enabled = logText.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(logText)) }) { Text("Copy") }
            }
        }
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear developer logs?") },
        text = { Text("This permanently deletes the locally saved diagnostic files.") },
        confirmButton = {
            TextButton(onClick = {
                DeveloperLog.clear(context)
                confirmClear = false
                refreshLogs()
                Toast.makeText(context, "Developer logs cleared", Toast.LENGTH_SHORT).show()
            }) { Text("Clear logs") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )
}
