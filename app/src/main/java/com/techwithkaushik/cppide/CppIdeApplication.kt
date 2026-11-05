package com.techwithkaushik.cppide

import android.app.Application

/** Application entry point. Optional diagnostics are controlled from Developer Options. */
class CppIdeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeveloperLog.initialize(this)
    }
}
