package com.techwithkaushik.cppide

import android.app.Activity
import android.app.Application
import android.os.Build
import com.techwithkaushik.cppide.core.logging.DeveloperLog

/** Application entry point and process-wide diagnostics bootstrap. */
class CppIdeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DeveloperLog.initialize(this)
        DeveloperLog.info("Application", "Application created; version=\${BuildConfig.VERSION_NAME}; sdk=\${Build.VERSION.SDK_INT}")
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                DeveloperLog.debug("Lifecycle", "Activity created: \${activity.javaClass.simpleName}")
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                DeveloperLog.debug("Lifecycle", "Activity resumed: \${activity.javaClass.simpleName}")
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                DeveloperLog.debug("Lifecycle", "Activity destroyed: \${activity.javaClass.simpleName}")
            }
        })
    }
}
