package com.techwithkaushik.cppide

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val reportFile = writeCrashReport(this, thread, error)
                scheduleCrashReportActivity(this, reportFile)
            } catch (_: Throwable) {
                // Never let crash-reporting failure hide the original exception.
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
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

    private fun scheduleCrashReportActivity(context: Context, reportFile: File) {
        val intent = Intent(context, CrashReportActivity::class.java).apply {
            putExtra(CrashReportActivity.EXTRA_REPORT_PATH, reportFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            7319,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + 900L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, pending)
        } else {
            alarm.set(AlarmManager.RTC, triggerAt, pending)
        }
    }
}
