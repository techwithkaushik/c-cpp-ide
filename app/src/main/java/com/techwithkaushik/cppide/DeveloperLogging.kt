package com.techwithkaushik.cppide

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.techwithkaushik.cppide.core.designsystem.IdeDesignTokens
import com.techwithkaushik.cppide.feature.terminal.DeveloperLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Opt-in, bounded app diagnostics. It deliberately does not read global Logcat:
 * Android restricts that on production devices. Only events sent through this
 * logger and uncaught app-process crashes are collected.
 */
@Composable
internal fun DeveloperOptionsPanel() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(DeveloperLog.isEnabled(context)) }
    var exporting by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            exporting = true
            scope.launch {
                val result = runCatching { DeveloperLog.export(context, uri) }
                exporting = false
                Toast.makeText(context, if (result.isSuccess) "Developer logs exported" else "Could not export logs", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
        Card(colors = CardDefaults.cardColors(containerColor = IdeDesignTokens.Color.Surface)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text("Developer logging", color = IdeDesignTokens.Color.TextPrimary, fontSize = 13.sp)
                    Text("Save app diagnostics and unexpected crashes locally. Logging is off by default.", color = IdeDesignTokens.Color.TextSecondary, fontSize = 11.sp)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    DeveloperLog.setEnabled(context, it)
                })
            }
        }
        Text(
            "Logs stay in private app storage. Passwords, tokens and private keys are redacted. Android does not allow ordinary apps to read the complete system Logcat, so this collects app-instrumented events and uncaught app-process crashes.",
            color = IdeDesignTokens.Color.TextSecondary,
            fontSize = 11.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(IdeDesignTokens.Spacing.sm)) {
            OutlinedButton(
                enabled = DeveloperLog.hasLogs(context) && !exporting,
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    exportPicker.launch("code-studio-logs-$stamp.zip")
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (exporting) "Exporting…" else "Export logs") }
            OutlinedButton(
                enabled = DeveloperLog.hasLogs(context),
                onClick = {
                    DeveloperLog.clear(context)
                    Toast.makeText(context, "Developer logs cleared", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Clear logs") }
        }
    }
}
