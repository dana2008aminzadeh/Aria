package ir.aria.downloader

import android.app.Application
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.service.NotificationSupport
import ir.aria.downloader.util.Logs

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Install the crash recorder before anything else can fail, so even a failure during
        // startup produces a readable report the user can send us.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { Logs.crash(this, thread, error) }
            previous?.uncaughtException(thread, error)
        }
        val uncleanExit = runCatching { Logs.beginSession(this) }.getOrDefault(false)
        if (uncleanExit) Logs.d(this, "app", "previous session did not shut down cleanly")
        Logs.d(this, "app", "onCreate")
        runCatching { NotificationSupport.createChannels(this) }
            .onFailure { Logs.e(this, "notification", "channel creation failed", it) }

        // Never let queue loading break app start: a corrupt queue is already repaired by the store.
        runCatching { DownloadRepository.get(this) }
            .onFailure { Logs.e(this, "app", "repository init failed", it) }
    }
}
