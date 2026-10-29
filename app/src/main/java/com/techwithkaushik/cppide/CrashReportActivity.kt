package com.techwithkaushik.cppide

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Simple crash recovery screen that does not depend on Compose or the editor UI. */
class CrashReportActivity : Activity() {
    companion object {
        const val EXTRA_REPORT_PATH = "com.techwithkaushik.cppide.extra.CRASH_REPORT_PATH"
    }

    private var reportText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportText = loadReport(intent?.getStringExtra(EXTRA_REPORT_PATH))
        showReport()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reportText = loadReport(intent.getStringExtra(EXTRA_REPORT_PATH))
        showReport()
    }

    private fun loadReport(path: String?): String {
        val candidate = path?.let(::File)
        val expected = File(filesDir, "crash-report/latest.txt").canonicalFile
        return runCatching {
            if (candidate != null && candidate.canonicalFile == expected && expected.isFile) {
                expected.readText()
            } else if (expected.isFile) {
                expected.readText()
            } else {
                "No saved crash report was found. The crash handler may not have completed."
            }
        }.getOrElse { "Unable to read crash report: ${it.message}" }
    }

    private fun showReport() {
        val background = 0xFF10151D.toInt()
        val foreground = 0xFFE7EDF5.toInt()
        val muted = 0xFF9AA8B8.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
            setBackgroundColor(background)
        }
        val title = TextView(this).apply {
            text = "App crashed"
            textSize = 25f
            setTextColor(foreground)
        }
        val subtitle = TextView(this).apply {
            text = "A crash report was saved on this device. Copy it to share with the developer."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(8), 0, dp(16))
        }
        val copy = Button(this).apply {
            text = "Copy crash report"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Code Studio crash report", reportText))
                Toast.makeText(this@CrashReportActivity, "Crash report copied", Toast.LENGTH_SHORT).show()
            }
        }
        val details = TextView(this).apply {
            text = reportText
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(foreground)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF1A222D.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(details, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(copy, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
