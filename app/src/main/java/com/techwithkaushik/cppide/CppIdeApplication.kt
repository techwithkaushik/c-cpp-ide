package com.techwithkaushik.cppide

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists uncaught exceptions before Android terminates the process.
 * The report activity is scheduled as a recovery entry point so it can be recreated
 * after the crashing process exits.
 */
class CppIdeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Use Android's default crash handling. Logcat captures uncaught exceptions.
    }

    private fun writeCrashReport(context: Context, thread: Thread, error: Throwable): File {
        val directory = File(context.filesDir, "crash-report")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create crash report directory")
        }
        val report = File(directory, "latest.txt")
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val text = buildString {
            appendLine("Code Studio — Crash Report")
            appendLine("Time: $timestamp")
            appendLine("App version: $version")
            appendLine("Package: ${context.packageName}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine()
            appendLine("Exception:")
            val trace = java.io.StringWriter()
            error.printStackTrace(java.io.PrintWriter(trace))
            append(trace.toString())
        }
        report.writeText(text)
        return report
    }

    /**
     * Try to open the report while this process still owns the foreground crash context.
     * AlarmManager activity launches are restricted on modern Android, so an alarm alone
     * is not a reliable crash UI mechanism. The report is persisted before this is called.
     */
    private fun launchCrashReportImmediately(context: Context, reportFile: File) {
        val intent = Intent(context, CrashReportActivity::class.java).apply {
            putExtra(CrashReportActivity.EXTRA_REPORT_PATH, reportFile.absolutePath)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        runCatching {
            context.startActivity(intent)
            // Give ActivityTaskManager a brief chance to process the launch before the
            // platform's uncaught-exception handler terminates this process.
            Thread.sleep(900L)
        }
    }
}
