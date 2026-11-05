package com.techwithkaushik.cppide.core.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opt-in, app-private diagnostics shared by the app and runtime integrations. */
object DeveloperLog {
    private const val PREFS = "developer_options"
    private const val ENABLED = "logging_enabled"
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_FILES = 4
    private const val MAX_READ_CHARS = 180_000
    private val lock = Any()
    private val initialized = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!initialized.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(app, "FATAL", "Crash", "Uncaught exception on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        info(app, "Application", "Process started; sdk=${Build.VERSION.SDK_INT}; abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun isEnabled(): Boolean = appContext?.let(::isEnabled) == true

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) {
            info(app, "DeveloperLog", "Developer logging enabled")
        }
    }

    fun debug(context: Context, tag: String, message: String) = write(context, "DEBUG", tag, message)
    fun info(context: Context, tag: String, message: String) = write(context, "INFO", tag, message)
    fun warn(context: Context, tag: String, message: String, error: Throwable? = null) = write(context, "WARN", tag, message, error)
    fun error(context: Context, tag: String, message: String, error: Throwable? = null) = write(context, "ERROR", tag, message, error)
    fun debug(tag: String, message: String) { appContext?.let { debug(it, tag, message) } }
    fun info(tag: String, message: String) { appContext?.let { info(it, tag, message) } }
    fun warn(tag: String, message: String, error: Throwable? = null) { appContext?.let { warn(it, tag, message, error) } }
    fun error(tag: String, message: String, error: Throwable? = null) { appContext?.let { error(it, tag, message, error) } }

    /** Writes stable key/value events for later diagnostics and regression analysis. */
    fun event(tag: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        val safeEvent = event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val fieldText = fields.toSortedMap().entries.joinToString(" ") { entry ->
            val key = entry.key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
            val value = (entry.value?.toString() ?: "null").replace(Regex("\\s+"), "_").replace("|", "_").take(512)
            "$key=$value"
        }
        info(tag, if (fieldText.isBlank()) "event=$safeEvent" else "event=$safeEvent $fieldText")
    }

    fun failure(tag: String, event: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        val safeEvent = event.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val fieldText = fields.toSortedMap().entries.joinToString(" ") { entry ->
            val key = entry.key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
            val value = (entry.value?.toString() ?: "null").replace(Regex("\\s+"), "_").replace("|", "_").take(512)
            "$key=$value"
        }
        val message = "event=$safeEvent $fieldText exceptionType=${error.javaClass.name} exceptionMessage=${error.message ?: "(no message)"}"
        error(tag, message, error)
    }

    private fun write(context: Context, level: String, tag: String, message: String, error: Throwable? = null) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        val details = buildString {
            append(message)
            if (error != null) { append('\n'); append(error.stackTraceToString()) }
        }
        val safe = redactSensitiveValues(details).take(24_000)
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = "$timestamp $level/$tag [pid=${Process.myPid()} thread=${Thread.currentThread().name}]: $safe\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            runCatching {
                val directory = File(app.filesDir, "developer-logs")
                if (!directory.exists() && !directory.mkdirs()) return@runCatching
                val active = File(directory, "app.log")
                if (active.exists() && active.length() + bytes.size > MAX_FILE_BYTES) rotate(directory, active)
                active.appendBytes(bytes)
                directory.listFiles()?.filter { it.isFile && it.name.startsWith("app") }
                    ?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
            }
        }
    }

    private fun rotate(directory: File, active: File) {
        File(directory, "app.3.log").delete()
        for (index in 2 downTo 1) {
            val from = File(directory, "app.$index.log")
            if (from.exists()) from.renameTo(File(directory, "app.${index + 1}.log"))
        }
        active.renameTo(File(directory, "app.1.log"))
    }

    internal fun redactSensitiveValues(value: String): String {
        var result = Regex("""(?i)-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?-----END [^-]*PRIVATE KEY-----""")
            .replace(value, "[PRIVATE KEY REDACTED]")
        result = Regex("""(?i)\b(bearer)\s+[A-Za-z0-9._~+/=-]+""").replace(result, "$1 [REDACTED]")
        val patterns = listOf(
            Regex("""(?i)\b(password|passwd|passphrase|token|secret|authorization|api[_-]?key|access[_-]?key)\b(\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;]+)"""),
            Regex("""(?i)\b(private[_ -]?key)\b(\s*[=:]\s*)[^\n]+"""),
            Regex("""(?i)([?&](?:token|key|password|secret)=)[^&\s]+"""),
            Regex("""(?i)(https?://[^/\s:@]+:)[^@\s]+@""")
        )
        patterns.forEach { pattern ->
            result = pattern.replace(result) { match ->
                match.groupValues[1] + match.groupValues.getOrElse(2) { "" } + "[REDACTED]"
            }
        }
        return result
    }

    private fun logFiles(context: Context): List<File> {
        val dir = File(context.applicationContext.filesDir, "developer-logs")
        return listOf("app.3.log", "app.2.log", "app.1.log", "app.log")
            .map { File(dir, it) }.filter { it.isFile && it.length() > 0L }
    }

    fun read(context: Context, maxChars: Int = MAX_READ_CHARS): String = synchronized(lock) {
        val output = buildString { logFiles(context).forEach { file -> runCatching { append(file.readText(Charsets.UTF_8)) } } }
        val limit = maxChars.coerceIn(1, MAX_READ_CHARS)
        if (output.length <= limit) output else "[Earlier log content omitted; showing the latest entries.]\n" + output.takeLast(limit)
    }

    fun hasLogs(context: Context): Boolean = logFiles(context).isNotEmpty()
    fun fileCount(context: Context): Int = logFiles(context).size
    fun totalBytes(context: Context): Long = logFiles(context).sumOf { it.length() }

    fun clear(context: Context) {
        synchronized(lock) { File(context.applicationContext.filesDir, "developer-logs").deleteRecursively() }
    }

    suspend fun export(context: Context, destination: Uri) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val snapshots = synchronized(lock) { logFiles(app).map { it.name to it.readBytes() } }
        app.contentResolver.openOutputStream(destination, "w")?.use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write(("Code Studio developer diagnostics. Sensitive values are redacted. Logging is opt-in and contains only app-instrumented events and uncaught app-process crashes. Android system logs and other apps are never read.\n").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("device-info.txt"))
                val version = runCatching {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
                }.getOrDefault("unknown")
                val metadata = buildString {
                    append("appVersion=").append(version).append('\n')
                    append("androidRelease=").append(Build.VERSION.RELEASE).append('\n')
                    append("androidSdk=").append(Build.VERSION.SDK_INT).append('\n')
                    append("supportedAbis=").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
                    append("exportedAt=").append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())).append('\n')
                }
                zip.write(metadata.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                snapshots.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } ?: error("Could not create the selected export file.")
    }
}
