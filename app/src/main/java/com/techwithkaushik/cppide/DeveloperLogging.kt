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
internal object DeveloperLog {
    private const val PREFS = "developer_options"
    private const val ENABLED = "logging_enabled"
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_FILES = 4
    private val lock = Any()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) info(context, "DeveloperLog", "Developer logging enabled")
        else info(context, "DeveloperLog", "Developer logging disabled")
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(app, "FATAL", "Crash", "Uncaught exception on thread ${thread.name}: ${error.stackTraceToString()}")
            previous?.uncaughtException(thread, error)
        }
        info(app, "Application", "Application process started")
    }

    fun debug(context: Context, tag: String, message: String) = write(context, "DEBUG", tag, message)
    fun info(context: Context, tag: String, message: String) = write(context, "INFO", tag, message)
    fun warn(context: Context, tag: String, message: String, error: Throwable? = null) =
        write(context, "WARN", tag, message + (error?.let { "\n" + it.stackTraceToString() } ?: ""))
    fun error(context: Context, tag: String, message: String, error: Throwable? = null) =
        write(context, "ERROR", tag, message + (error?.let { "\n" + it.stackTraceToString() } ?: ""))

    private fun write(context: Context, level: String, tag: String, raw: String) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        val safe = redact(raw).take(24_000)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = "$timestamp $level/$tag: $safe\n"
        synchronized(lock) {
            runCatching {
                val dir = File(app.filesDir, "developer-logs")
                if (!dir.exists()) dir.mkdirs()
                val active = File(dir, "app.log")
                if (active.exists() && active.length() + line.toByteArray().size > MAX_FILE_BYTES) {
                    val oldest = File(dir, "app.3.log")
                    oldest.delete()
                    for (index in 2 downTo 1) {
                        val from = File(dir, "app.$index.log")
                        if (from.exists()) from.renameTo(File(dir, "app.${index + 1}.log"))
                    }
                    active.renameTo(File(dir, "app.1.log"))
                }
                active.appendText(line)
                dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_FILES)?.forEach(File::delete)
            }
        }
    }

    private fun redact(value: String): String {
        var result = value
        val patterns = listOf(
            Regex("(?i)(password|passwd|passphrase|token|secret|authorization)\\s*([=:])\\s*[^\\s,;]+"),
            Regex("(?i)(private[_ -]?key)\\s*([=:])\\s*[^\\n]+"),
            Regex("(?i)(-----BEGIN [^-]*PRIVATE KEY-----)[\\s\\S]*?(-----END [^-]*PRIVATE KEY-----)")
        )
        patterns.forEach { pattern -> result = pattern.replace(result) { match ->
            if (match.groupValues.size >= 3 && match.groupValues[1].isNotBlank()) "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
            else "[PRIVATE KEY REDACTED]"
        } }
        return result
    }

    fun clear(context: Context) {
        synchronized(lock) { File(context.applicationContext.filesDir, "developer-logs").deleteRecursively() }
    }

    suspend fun export(context: Context, destination: Uri) = withContext(Dispatchers.IO) {
        val dir = File(context.applicationContext.filesDir, "developer-logs")
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("Code Studio developer diagnostics. Sensitive values are redacted. Logs are opt-in and limited to app-instrumented events and uncaught app-process crashes; this is not a full Android Logcat dump.\n".toByteArray())
                zip.closeEntry()
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Could not create the selected export file.")
    }

    fun hasLogs(context: Context): Boolean =
        File(context.applicationContext.filesDir, "developer-logs").listFiles()?.any { it.isFile && it.length() > 0 } == true
}

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
                enabled = enabled && !exporting,
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
