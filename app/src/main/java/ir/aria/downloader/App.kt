package ir.aria.downloader

import android.app.Application
import android.util.Log
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.service.NotificationSupport

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { NotificationSupport.createChannels(this) }
            .onFailure { Log.e("AriaApp", "channel creation failed", it) }

        // Never let queue loading break app start: a corrupt queue is already repaired by the store.
        runCatching { DownloadRepository.get(this) }
            .onFailure { Log.e("AriaApp", "repository init failed", it) }
    }
}
