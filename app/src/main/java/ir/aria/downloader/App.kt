package ir.aria.downloader

import android.app.Application
import android.os.Build
import android.util.Log
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.service.NotificationSupport
import ir.aria.downloader.util.AppLog

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        installCrashLogger()
        AppLog.log(
            "شروع",
            "=== AllDown ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) " +
                "| Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) " +
                "| ${Build.MANUFACTURER} ${Build.MODEL} ==="
        )

        runCatching { NotificationSupport.createChannels(this) }
            .onFailure {
                Log.e("AriaApp", "channel creation failed", it)
                AppLog.log("شروع", "ساخت کانال‌های اعلان ناموفق بود", it)
            }

        // Never let queue loading break app start: a corrupt queue is already repaired by the store.
        runCatching { DownloadRepository.get(this) }
            .onFailure {
                Log.e("AriaApp", "repository init failed", it)
                AppLog.log("شروع", "بارگذاری صف ناموفق بود", it)
            }
    }

    /** Records uncaught crashes into the shareable log before the process dies. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLog.log("کرش", "thread=${thread.name}", error)
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
