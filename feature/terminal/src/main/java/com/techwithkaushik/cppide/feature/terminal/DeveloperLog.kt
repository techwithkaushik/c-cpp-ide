package com.techwithkaushik.cppide.feature.terminal

import android.content.Context
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeveloperLog {
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
        result = Regex("(?i)-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?-----END [^-]*PRIVATE KEY-----")
            .replace(result, "[PRIVATE KEY REDACTED]")
        val patterns = listOf(
            Regex("(?i)(password|passwd|passphrase|token|secret|authorization)\\s*([=:])\\s*[^\\s,;]+"),
            Regex("(?i)(private[_ -]?key)\\s*([=:])\\s*[^\\n]+")
        )
        patterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
            }
        }
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
