package com.techwithkaushik.cppide

import android.app.Application
import com.techwithkaushik.cppide.feature.terminal.DeveloperLog

/** Application entry point. Optional diagnostics are controlled from Developer Options. */
class CppIdeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeveloperLog.initialize(this)
    }
}
