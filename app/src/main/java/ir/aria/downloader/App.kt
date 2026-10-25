package ir.aria.downloader

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.diag.DiagLog
import ir.aria.downloader.service.NotificationSupport

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // First thing: in the `diag` build this opens the log file (session header) and arms
        // the crash recorder, so even a failure during the lines below is captured.
        DiagLog.init(this)
        DiagLog.installCrashHandler()
        DiagLog.i("App", "App.onCreate (diagnostics=" + DiagLog.enabled + ")")
        if (DiagLog.enabled) registerActivityLifecycleLogger()

        runCatching { NotificationSupport.createChannels(this) }
            .onSuccess { DiagLog.d("App", "notification channels created") }
            .onFailure { DiagLog.e("App", "channel creation failed", it) }

        // Never let queue loading break app start: a corrupt queue is already repaired by the store.
        runCatching { DownloadRepository.get(this) }
            .onFailure { DiagLog.e("App", "repository init failed", it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DiagLog.w("App", "onTrimMemory level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        DiagLog.w("App", "onLowMemory")
    }

    /** In diag builds every screen transition lands in the log without touching each activity. */
    private fun registerActivityLifecycleLogger() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun name(activity: Activity) = activity.javaClass.simpleName

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                DiagLog.d(
                    "UI", name(activity) + ".onCreate restored=" + (savedInstanceState != null) +
                        " intent=" + activity.intent?.action
                )
            }

            override fun onActivityStarted(activity: Activity) {
                DiagLog.d("UI", name(activity) + ".onStart")
            }

            override fun onActivityResumed(activity: Activity) {
                DiagLog.d("UI", name(activity) + ".onResume")
            }

            override fun onActivityPaused(activity: Activity) {
                DiagLog.d("UI", name(activity) + ".onPause finishing=" + activity.isFinishing)
            }

            override fun onActivityStopped(activity: Activity) {
                DiagLog.d("UI", name(activity) + ".onStop")
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                DiagLog.d("UI", name(activity) + ".onDestroy")
            }
        })
    }
}
