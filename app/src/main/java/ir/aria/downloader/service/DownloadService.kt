package ir.aria.downloader.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ir.aria.downloader.R
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.data.FileSaver
import ir.aria.downloader.data.Settings
import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.net.FileNames
import ir.aria.downloader.net.HttpDownloader
import ir.aria.downloader.net.MediaProcessor
import ir.aria.downloader.net.MimeTypes
import ir.aria.downloader.net.Mp4Remuxer
import ir.aria.downloader.ui.MainActivity
import ir.aria.downloader.util.AppLog
import ir.aria.downloader.util.Formats
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns the whole download engine:
 *
 *  - native resumable HTTP engine for direct file links
 *  - yt-dlp extraction engine (via Chaquopy) for media pages, with on-device MP4 remuxing
 *  - queueing with a concurrency limit, auto retry, wi-fi-only mode and notifications
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_PROGRESS = NotificationSupport.CHANNEL_PROGRESS
        const val CHANNEL_FINISHED = NotificationSupport.CHANNEL_FINISHED
        private const val FOREGROUND_ID = 1

        const val ACTION_START = "ir.aria.downloader.action.START"
        const val ACTION_PAUSE = "ir.aria.downloader.action.PAUSE"
        const val ACTION_RESUME = "ir.aria.downloader.action.RESUME"
        const val ACTION_PAUSE_ALL = "ir.aria.downloader.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "ir.aria.downloader.action.RESUME_ALL"
        const val ACTION_DELETE = "ir.aria.downloader.action.DELETE"
        const val ACTION_RETRY = "ir.aria.downloader.action.RETRY"
        const val ACTION_CLEAR_COMPLETED = "ir.aria.downloader.action.CLEAR_COMPLETED"
        const val ACTION_SETTINGS_CHANGED = "ir.aria.downloader.action.SETTINGS_CHANGED"

        const val EXTRA_ID = "extra_id"
        const val EXTRA_DELETE_FILE = "extra_delete_file"

        fun send(context: Context, action: String, id: Long = -1L, deleteFile: Boolean = false) {
            val intent = Intent(context, DownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_DELETE_FILE, deleteFile)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Background-start restrictions: the queue is persisted and resumes later.
                Log.e("AriaService", "cannot start foreground service", e)
            }
        }
    }

    /** Thrown when a "file" link actually returns a web page. */
    private class HtmlPageException : IOException("html page")

    private lateinit var repo: DownloadRepository
    private lateinit var settings: Settings
    private val crashGuard = CoroutineExceptionHandler { _, error ->
        Log.e("AriaService", "unhandled coroutine error", error)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val downloaders = ConcurrentHashMap<Long, HttpDownloader>()
    private val scheduleMutex = Mutex()

    @Volatile private var foregroundShown = false
    private var lastNotificationAt = 0L
    private var lastNotificationErrorAt = 0L
    private var idleStopJob: Job? = null

    @Volatile private var networkAvailable = true
    @Volatile private var onWifi = true

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        repo = DownloadRepository.get(this)
        settings = repo.settings
        AppLog.log(
            "سرویس",
            "سرویس دانلود ساخته شد | همزمانی=${settings.concurrency} " +
                "اتصال‌موازی=${settings.fragmentConcurrency} تلاش‌خودکار=${settings.autoRetry} " +
                "فقط‌وای‌فای=${settings.wifiOnly} پروکسی=${settings.proxyUrl.isNotBlank()} " +
                "کیفیت‌پیش‌فرض=${settings.defaultPreset}"
        )
        NotificationSupport.createChannels(this)
        updateNetworkFlags()
        startForegroundCompat(buildProgressNotification())
        registerNetworkWatcher()
        requestSchedule()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> handlePause(intent.getLongExtra(EXTRA_ID, -1L))
            ACTION_RESUME, ACTION_RETRY -> handleResume(intent.getLongExtra(EXTRA_ID, -1L))
            ACTION_DELETE -> handleDelete(
                intent.getLongExtra(EXTRA_ID, -1L),
                intent.getBooleanExtra(EXTRA_DELETE_FILE, false)
            )
            ACTION_PAUSE_ALL -> handlePauseAll()
            ACTION_RESUME_ALL -> handleResumeAll()
            ACTION_CLEAR_COMPLETED -> handleClearCompleted(intent.getBooleanExtra(EXTRA_DELETE_FILE, false))
            ACTION_SETTINGS_CHANGED -> handleSettingsChanged()
            else -> Unit
        }
        if (settings.concurrency > 0) requestSchedule()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        networkCallback?.let { runCatching { connectivityManager().unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    // ================================================================== scheduling

    private fun requestSchedule() {
        scope.launch { scheduleMutex.withLock { scheduleLocked() } }
    }

    private suspend fun scheduleLocked() {
        updateNetworkFlags()
        val list = repo.items.value
        val queued = list.filter { it.status == DownloadStatus.QUEUED && !jobs.containsKey(it.id) }
            .sortedBy { it.createdAt }

        val blockedByWifi = settings.wifiOnly && !onWifi
        val blockedByNetwork = !networkAvailable
        if ((blockedByWifi || blockedByNetwork) && queued.isNotEmpty()) {
            updateProgressNotification(force = true)
            scheduleIdleStop()
            return
        }

        val max = settings.concurrency
        var running = jobs.size
        for (item in queued) {
            if (running >= max) break
            if (jobs.containsKey(item.id)) continue
            if (repo.item(item.id) == null) continue
            val job = scope.launch {
                try {
                    runItem(item.id)
                } catch (t: Throwable) {
                    // ANY unexpected failure (python, storage, mp4parser, OEM quirks) is reported
                    // on the item instead of taking the whole app down.
                    Log.e("AriaService", "item " + item.id + " failed", t)
                    runCatching {
                        repo.update(item.id, persist = true) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                speedBps = 0,
                                errorMessage = "خطای داخلی: " + t.javaClass.simpleName
                            )
                        }
                    }
                } finally {
                    jobs.remove(item.id)
                    downloaders.remove(item.id)
                    requestSchedule()
                }
            }
            jobs[item.id] = job
            running++
        }
        updateProgressNotification(force = true)
        maybeStopIfIdle()
    }

    private suspend fun runItem(id: Long) {
        var item = repo.item(id) ?: return
        // The job may have been launched a moment before the user pressed pause/delete:
        // never resurrect an entry that is no longer queued.
        if (item.status != DownloadStatus.QUEUED) return
        Log.d("AriaService", "start item " + id + " (" + item.engine + ", preset=" + item.preset + ")")
        AppLog.log(
            "صف",
            "شروع آیتم $id | موتور=${item.engine} preset=${item.preset} " +
                "تلاش=${item.attempts} انتخاب=${item.selectionLabel ?: "-"} | ${item.url}"
        )
        repo.update(id, persist = true) {
            it.copy(status = DownloadStatus.RUNNING, speedBps = 0, errorMessage = null)
        }
        item = repo.item(id) ?: return
        updateProgressNotification(force = true)
        if (item.engine == EngineKind.HTTP) runHttpItem(item) else runMediaItem(item)
    }

    // ================================================================== http engine

    private suspend fun runHttpItem(startItem: DownloadItem) {
        val id = startItem.id
        val part = repo.partFile(id)
        val downloader = HttpDownloader(settings.userAgent)
        downloaders[id] = downloader
        var item = startItem
        var switched = false
        var wifiLost = false
        var currentProgress = 0L

        val listener = object : HttpDownloader.Listener {
            override fun onMetadata(metadata: HttpDownloader.Metadata) {
                val type = metadata.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                if (type == "text/html" && !looksLikeWebFile(item.url)) {
                    switched = true
                    throw HtmlPageException()
                }
                val (name, mime) = resolveName(item, metadata)
                repo.update(id) { current ->
                    current.copy(
                        fileName = name,
                        mimeType = mime,
                        totalBytes = if (metadata.totalBytes > 0) metadata.totalBytes else current.totalBytes,
                        etag = metadata.etag,
                        lastModified = metadata.lastModified,
                        supportsResume = metadata.resumable
                    )
                }
                item = repo.item(id) ?: item
            }

            override fun onProgress(
                downloadedBytes: Long,
                totalBytes: Long,
                speedBps: Long,
                etaSeconds: Long
            ) {
                if (settings.wifiOnly && !isCurrentlyOnWifi()) {
                    wifiLost = true
                    throw IOException("wifi lost")
                }
                currentProgress = downloadedBytes
                repo.updateProgress(id, downloadedBytes, totalBytes, speedBps)
                updateProgressNotification(force = false)
            }
        }

        try {
            AppLog.log("HTTP", "شروع آیتم $id | ${item.url}")
            downloader.download(item.url, part, item.etag ?: item.lastModified, listener)
            AppLog.log("HTTP", "پایان دانلود آیتم $id | حجم فایل موقت=${part.length()}B")
            finishHttpSuccess(id, part)
        } catch (e: HtmlPageException) {
            AppLog.log("HTTP", "آیتم $id صفحهٔ وب بود؛ به موتور رسانه‌ای واگذار شد")
            part.delete()
            repo.update(id, persist = true) {
                it.copy(
                    engine = EngineKind.MEDIA,
                    status = DownloadStatus.QUEUED,
                    downloadedBytes = 0,
                    totalBytes = -1,
                    attempts = 0,
                    errorMessage = null,
                    mimeType = "video/mp4"
                )
            }
        } catch (e: HttpDownloader.CancelledException) {
            val current = repo.item(id) ?: return
            if (current.status == DownloadStatus.RUNNING) {
                repo.update(id, persist = true) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0) }
            }
        } catch (e: IOException) {
            handleError(id, e, wifiLost, currentProgress)
        } finally {
            downloaders.remove(id)
        }
        if (switched) requestSchedule()
    }

    private fun looksLikeWebFile(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php") ||
            path.endsWith(".asp") || path.endsWith(".aspx") || path.endsWith(".jsp")
    }

    private fun finishHttpSuccess(id: Long, part: File) {
        val item = repo.item(id) ?: run { part.delete(); return }
        if (!part.exists()) {
            repo.update(id, persist = true) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = "فایل موقت پیدا نشد", speedBps = 0)
            }
            return
        }
        val size = part.length()
        val saved = try {
            FileSaver.save(this, part, item.fileName, item.mimeType, emptySet())
        } catch (e: Exception) {
            repo.update(id, persist = true) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = "ذخیره فایل ناموفق بود", speedBps = 0)
            }
            notifyFailed(id)
            return
        }
        part.delete()
        repo.update(id, persist = true) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                fileName = saved.displayName,
                fileUri = saved.uri,
                downloadedBytes = size,
                totalBytes = if (it.totalBytes > 0) it.totalBytes else size,
                completedAt = System.currentTimeMillis(),
                errorMessage = null,
                speedBps = 0,
                attempts = 0,
                pausedByUser = false
            )
        }
        AppLog.log("HTTP", "آیتم $id کامل شد | ${saved.displayName} | ${size}B | uri=${saved.uri}")
        notifyCompleted(id)
        updateProgressNotification(force = true)
    }

    // ================================================================== media engine

    private suspend fun runMediaItem(startItem: DownloadItem) {
        val id = startItem.id
        val engine = ExtractorEngine.get(this)
        val work = repo.mediaWorkDir(id)
        val shouldCancel = { repo.item(id)?.status != DownloadStatus.RUNNING }

        fun publish(downloaded: Long, total: Long, speed: Long) {
            repo.updateProgress(id, downloaded, total, speed)
            updateProgressNotification(force = false)
        }

        val selection = startItem.exactSelection()
        Log.d("AriaService", "resolve " + startItem.url + " preset=" + startItem.preset +
            " exact=" + (selection?.label ?: "legacy"))
        val plan = engine.resolve(startItem.url, startItem.preset, selection)
        if (!plan.ok) {
            Log.e("AriaService", "resolve failed: " + plan.error)
            val message = plan.error ?: getString(R.string.download_failed)
            // Extraction is the flakiest phase on anti-bot sites (Pornhub!): a failed
            // resolve gets the same automatic retry as a failed download instead of
            // dying instantly and forcing the user to tap retry by hand.
            if (!scheduleRetry(id, startItem, message)) failItem(id, message)
            return
        }
        Log.d("AriaService", "plan mode=" + plan.mode + " v=" + plan.videoId + " a=" + plan.audioId + " f=" + plan.formatId)
        if (shouldCancel()) {
            repo.update(id, persist = true) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0) }
            return
        }

        val ext = plan.ext.lowercase().ifBlank { "mp4" }
        val title = plan.title.ifBlank { startItem.fileName }
        val desiredName = buildFileName(title, ext)
        val mime = MimeTypes.mimeForExtension(ext).takeIf { it != "application/octet-stream" }
            ?: if (plan.audioOnly) "application/octet-stream" else "video/mp4"
        val plannedSize = plan.filesize
        repo.update(id, persist = true) {
            it.copy(
                fileName = desiredName,
                mimeType = mime,
                totalBytes = if (plannedSize > 0) plannedSize else -1,
                extractor = plan.extractor,
                height = plan.height,
                durationSec = plan.durationSec,
                title = title,
                warning = plan.warning,
                selectionLabel = plan.note.ifBlank { it.selectionLabel.orEmpty() },
                status = DownloadStatus.RUNNING
            )
        }

        if (plan.mode == "merge" && plan.videoId != null && plan.audioId != null) {
            val videoOutcome = engine.download(
                startItem.url, work, plan.videoId, "best", shouldCancel
            ) { p ->
                val total = if (plannedSize > 0) plannedSize else p.total
                publish(p.downloaded, total, p.speed)
            }
            if (!videoOutcome.ok) {
                stopMedia(id, videoOutcome)
                return
            }
            val videoFile = File(videoOutcome.files.first().path)
            val baseBytes = videoFile.length()
            val audioOutcome = engine.download(
                startItem.url, work, plan.audioId, "audio", shouldCancel
            ) { p ->
                val total = when {
                    plannedSize > 0 -> plannedSize
                    p.total > 0 -> baseBytes + p.total
                    else -> -1L
                }
                publish(baseBytes + p.downloaded, total, p.speed)
            }
            if (!audioOutcome.ok) {
                stopMedia(id, audioOutcome)
                return
            }
            val audioFile = File(audioOutcome.files.first().path)

            val combinedBytes = baseBytes + audioFile.length()
            publish(combinedBytes, if (plannedSize > 0) plannedSize else combinedBytes, 0)
            val merged = File(work, "merged-${id}.$ext")
            val processed = try {
                val fast = if (selection == null && ext == "mp4") {
                    runCatching { Mp4Remuxer.remux(videoFile, audioFile, merged) }.getOrNull()
                } else null
                fast?.file ?: MediaProcessor.merge(videoFile, audioFile, merged)
            } catch (e: Exception) {
                Log.e("AriaService", "merge/transcode failed for item $id", e)
                null
            }
            if (processed == null) {
                if (selection == null) {
                    // Preserve the old queue's best-effort behavior only for legacy presets.
                    val warning = getString(R.string.merge_failed_warning)
                    finishMedia(id, videoFile, buildFileName(title, "mp4"), "video/mp4", warning)
                } else {
                    failItem(id, getString(R.string.media_processing_failed))
                    return
                }
            } else {
                if (shouldCancel()) {
                    repo.update(id, persist = true) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0) }
                    return
                }
                finishMedia(id, processed, buildFileName(title, ext), mime, null)
            }
        } else {
            val outcome = engine.download(
                startItem.url, work, plan.formatId, startItem.preset, shouldCancel
            ) { p ->
                publish(p.downloaded, if (p.total > 0) p.total else plannedSize, p.speed)
            }
            if (!outcome.ok) {
                stopMedia(id, outcome)
                return
            }
            val source = File(outcome.files.first().path)
            val processed = try {
                when (plan.postprocess) {
                    "audio" -> MediaProcessor.convertAudio(
                        source, File(work, "audio-${id}.$ext"), ext, plan.audioBitrateKbps
                    )
                    "remux" -> MediaProcessor.remux(
                        source, File(work, "remuxed-${id}.$ext"),
                        includeAudio = plan.outputKind != "video_only"
                    )
                    else -> source
                }
            } catch (e: Exception) {
                Log.e("AriaService", "output processing failed for item $id", e)
                failItem(id, getString(R.string.media_processing_failed))
                return
            }
            if (shouldCancel()) {
                repo.update(id, persist = true) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0) }
                return
            }
            val finalExt = if (processed == source) FileNames.extensionOf(source.name).ifBlank { ext } else ext
            val finalMime = MimeTypes.mimeForExtension(finalExt).takeIf {
                it != "application/octet-stream"
            } ?: mime
            finishMedia(id, processed, buildFileName(title, finalExt), finalMime, null)
        }
        work.deleteRecursively()
        updateProgressNotification(force = true)
    }

    private fun buildFileName(title: String, ext: String): String =
        FileNames.withExtension(title, ext)

    private fun finishMedia(
        id: Long,
        source: File,
        fileName: String,
        mime: String,
        extraWarning: String?
    ) {
        if (!source.exists()) {
            failItem(id, getString(R.string.download_failed))
            return
        }
        val size = source.length()
        val saved = try {
            FileSaver.save(this, source, fileName, mime, emptySet())
        } catch (e: Exception) {
            AppLog.log("صف", "ذخیرهٔ آیتم $id ناموفق بود", e)
            failItem(id, "ذخیره فایل ناموفق بود")
            return
        }
        AppLog.log("صف", "آیتم $id کامل شد | ${saved.displayName} | ${size}B | $mime | uri=${saved.uri}")
        repo.update(id, persist = true) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                fileName = saved.displayName,
                fileUri = saved.uri,
                downloadedBytes = size,
                totalBytes = size,
                mimeType = mime,
                completedAt = System.currentTimeMillis(),
                errorMessage = null,
                speedBps = 0,
                attempts = 0,
                pausedByUser = false,
                warning = extraWarning ?: it.warning
            )
        }
        notifyCompleted(id)
    }

    private fun stopMedia(id: Long, outcome: ExtractorEngine.Outcome) {
        if (outcome.cancelled) {
            val current = repo.item(id) ?: return
            if (current.status == DownloadStatus.RUNNING) {
                repo.update(id, persist = true) { it.copy(status = DownloadStatus.PAUSED, speedBps = 0) }
            }
            return
        }
        val item = repo.item(id) ?: return
        val message = outcome.error ?: getString(R.string.download_failed)
        if (scheduleRetry(id, item, message)) return
        failItem(id, message)
    }

    /**
     * Re-queues a media item after a short backoff when auto-retry is on.
     * Used both for download failures and for plan/resolve failures, which used
     * to fail instantly with no retry at all. Returns true when a retry was scheduled.
     */
    private fun scheduleRetry(id: Long, item: DownloadItem, message: String): Boolean {
        if (!settings.autoRetry || item.attempts >= 2) {
            AppLog.log("صف", "آیتم $id دیگر خودکار تلاش نمی‌شود (autoRetry=${settings.autoRetry} تلاش‌ها=${item.attempts}): $message")
            return false
        }
        AppLog.log("صف", "آیتم $id تلاش مجدد خودکار ${item.attempts + 1} از 2، ${4 * (item.attempts + 1)} ثانیه دیگر: $message")
        repo.update(id, persist = true) {
            it.copy(status = DownloadStatus.FAILED, speedBps = 0, errorMessage = message, attempts = it.attempts + 1)
        }
        scope.launch {
            delay(4_000L * (item.attempts + 1))
            val current = repo.item(id) ?: return@launch
            if (current.status == DownloadStatus.FAILED && !current.pausedByUser) {
                repo.update(id, persist = true) { it.copy(status = DownloadStatus.QUEUED) }
                requestSchedule()
            }
        }
        return true
    }

    private fun failItem(id: Long, message: String) {
        val item = repo.item(id)
        AppLog.log("صف", "آیتم $id ناموفق شد | ${item?.url ?: ""} | $message")
        repo.update(id, persist = true) {
            it.copy(status = DownloadStatus.FAILED, speedBps = 0, errorMessage = message)
        }
        notifyFailed(id)
    }

    // ================================================================== errors

    private fun handleError(id: Long, error: IOException, wifiLost: Boolean, downloaded: Long) {
        val item = repo.item(id) ?: return
        if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.CANCELED) return

        if (wifiLost || !networkAvailable) {
            repo.update(id, persist = true) {
                it.copy(status = DownloadStatus.PAUSED, speedBps = 0, pausedByUser = false, errorMessage = null)
            }
            return
        }

        val message = when (error) {
            is HttpDownloader.HttpException -> httpMessage(error.code)
            else -> "خطای شبکه: " + (error.message ?: "اتصال قطع شد")
        }
        AppLog.log("HTTP", "خطای آیتم $id (دانلودشده=${downloaded}B): $message", error)
        val transient = error !is HttpDownloader.HttpException
        // A link that returns "connection closed early" is resumable, keep the progress.
        if (transient && settings.autoRetry && item.attempts < 3) {
            repo.update(id, persist = true) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    speedBps = 0,
                    errorMessage = message,
                    attempts = it.attempts + 1,
                    downloadedBytes = if (downloaded > 0) downloaded else it.downloadedBytes
                )
            }
            scope.launch {
                delay(3_000L * (item.attempts + 1))
                val current = repo.item(id) ?: return@launch
                if (current.status == DownloadStatus.FAILED && !current.pausedByUser) {
                    repo.update(id, persist = true) { it.copy(status = DownloadStatus.QUEUED) }
                    requestSchedule()
                }
            }
            return
        }
        repo.update(id, persist = true) {
            it.copy(status = DownloadStatus.FAILED, speedBps = 0, errorMessage = message)
        }
        notifyFailed(id)
    }

    private fun httpMessage(code: Int): String = when (code) {
        400 -> "درخواست نامعتبر (400)"
        401 -> "نیاز به ورود به حساب (401)"
        403 -> "دسترسی به این لینک رد شد (403)"
        404 -> "فایل پیدا نشد (404)"
        410 -> "فایل حذف شده است (410)"
        416 -> "خطا در ادامه دانلود (416)"
        429 -> "درخواست بیش از حد، بعداً دوباره تلاش کنید (429)"
        in 500..599 -> "خطای سرور ($code)"
        else -> "خطای HTTP $code"
    }

    // ================================================================== naming

    private fun resolveName(
        item: DownloadItem,
        metadata: HttpDownloader.Metadata
    ): Pair<String, String> {
        var name = FileNames.fromContentDisposition(metadata.contentDisposition)
            ?: FileNames.fromUrl(metadata.finalUrl)
            ?: FileNames.fromUrl(item.url)
        if (name == null) {
            name = item.fileName.takeIf { !DownloadRepository.isPlaceholderName(it) }
        }
        val mimeHint = metadata.contentType
        if (name == null) {
            val ext = MimeTypes.extensionForMime(mimeHint ?: "application/octet-stream")
            name = "file-${item.id}.$ext"
        }
        var finalName = FileNames.sanitize(name)
        val mime = MimeTypes.normalize(mimeHint, finalName)
        if (FileNames.extensionOf(finalName).isEmpty()) {
            val ext = MimeTypes.extensionForMime(mime)
            if (ext != "bin") finalName = "$finalName.$ext"
        }
        return finalName to mime
    }

    // ================================================================== commands

    private fun handlePause(id: Long) {
        if (id <= 0) return
        scope.launch {
            val item = repo.item(id) ?: return@launch
            if (item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.RUNNING) {
                repo.update(id, persist = true) {
                    it.copy(status = DownloadStatus.PAUSED, pausedByUser = true, speedBps = 0)
                }
                downloaders[id]?.abort()
            }
            requestSchedule()
        }
    }

    private fun handleResume(id: Long) {
        if (id <= 0) return
        scope.launch {
            val item = repo.item(id) ?: return@launch
            if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
                repo.update(id, persist = true) {
                    it.copy(
                        status = DownloadStatus.QUEUED,
                        pausedByUser = false,
                        speedBps = 0,
                        errorMessage = null,
                        attempts = 0
                    )
                }
            }
            requestSchedule()
        }
    }

    private fun handleDelete(id: Long, deleteFile: Boolean) {
        if (id <= 0) return
        scope.launch {
            downloaders[id]?.abort()
            jobs[id]?.cancel()
            val removed = repo.remove(id)
            repo.mediaWorkDir(id).deleteRecursively()
            if (deleteFile && removed != null) FileSaver.delete(this@DownloadService, removed)
            requestSchedule()
        }
    }

    private fun handlePauseAll() {
        scope.launch {
            for (item in repo.items.value) {
                if (item.status == DownloadStatus.RUNNING || item.status == DownloadStatus.QUEUED) {
                    repo.update(item.id, persist = true) {
                        it.copy(status = DownloadStatus.PAUSED, pausedByUser = true, speedBps = 0)
                    }
                    downloaders[item.id]?.abort()
                }
            }
            requestSchedule()
        }
    }

    private fun handleResumeAll() {
        scope.launch {
            for (item in repo.items.value) {
                if (item.status == DownloadStatus.PAUSED || item.status == DownloadStatus.FAILED) {
                    repo.update(item.id, persist = true) {
                        it.copy(
                            status = DownloadStatus.QUEUED,
                            pausedByUser = false,
                            speedBps = 0,
                            errorMessage = null,
                            attempts = 0
                        )
                    }
                }
            }
            requestSchedule()
        }
    }

    private fun handleSettingsChanged() {
        if (!settings.notificationsEnabled) {
            val manager = NotificationManagerCompat.from(this)
            for (item in repo.items.value) {
                runCatching { manager.cancel(30_000 + item.id.toInt()) }
                runCatching { manager.cancel(40_000 + item.id.toInt()) }
            }
        }
        updateProgressNotification(force = true)
    }

    private fun handleClearCompleted(deleteFiles: Boolean) {
        scope.launch {
            for (item in repo.items.value) {
                if (item.status == DownloadStatus.COMPLETED) {
                    val removed = repo.remove(item.id) ?: continue
                    if (deleteFiles) FileSaver.delete(this@DownloadService, removed)
                }
            }
            repo.persistNow()
            requestSchedule()
        }
    }

    // ================================================================== network

    private fun connectivityManager(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    private fun updateNetworkFlags() {
        val cm = connectivityManager()
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val info = runCatching { cm.activeNetworkInfo }.getOrNull()

        val wifiByCaps = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val wifiByInfo = info?.type == ConnectivityManager.TYPE_WIFI ||
            info?.type == ConnectivityManager.TYPE_ETHERNET
        onWifi = wifiByCaps || wifiByInfo

        networkAvailable = when {
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true -> true
            info != null -> info.isConnected
            else -> false
        }
    }

    private fun isCurrentlyOnWifi(): Boolean {
        updateNetworkFlags()
        return onWifi
    }

    private fun registerNetworkWatcher() {
        val cm = connectivityManager()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkFlags()
                resumeAutoPaused()
                requestSchedule()
            }

            override fun onLost(network: Network) {
                updateNetworkFlags()
                requestSchedule()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateNetworkFlags()
                if (networkAvailable) {
                    resumeAutoPaused()
                    requestSchedule()
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
    }

    private fun resumeAutoPaused() {
        if (!networkAvailable) return
        if (settings.wifiOnly && !onWifi) return
        val candidates = repo.items.value.filter { it.status == DownloadStatus.PAUSED && !it.pausedByUser }
        if (candidates.isEmpty()) return
        scope.launch {
            for (item in candidates) {
                repo.update(item.id, persist = true) { it.copy(status = DownloadStatus.QUEUED) }
            }
            requestSchedule()
        }
    }

    // ================================================================== notifications

    private fun mainPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(): Notification {
        val summary = repo.summary()
        val waiting = summary.activeCount == 0
        val title = if (waiting) {
            when {
                !networkAvailable -> getString(R.string.no_internet)
                settings.wifiOnly && !onWifi -> getString(R.string.waiting_wifi)
                else -> getString(R.string.queued)
            }
        } else {
            getString(R.string.downloads_running, summary.activeCount)
        }
        val text = when {
            waiting -> getString(R.string.empty_sub)
            summary.aggregateProgress != null ->
                summary.aggregateProgress.toString() + "% • " + Formats.speed(summary.aggregateSpeed)
            else -> Formats.speed(summary.aggregateSpeed)
        }
        val running = repo.items.value.filter { it.status == DownloadStatus.RUNNING }
        val expandedText = buildString {
            append(text)
            for (item in running.take(3)) {
                append('\n').append(item.fileName)
                if (item.totalBytes > 0) append(" • ").append(item.progressPercent).append('%')
            }
            if (running.size > 3) append('\n').append("+").append(running.size - 3)
            if (summary.queuedCount > 0) {
                append('\n').append(getString(R.string.queued)).append(": ").append(summary.queuedCount)
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setContentIntent(mainPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val progress = summary.aggregateProgress
        if (progress != null) builder.setProgress(100, progress, false)
        else builder.setProgress(0, 0, true)

        if (summary.activeCount > 0) {
            builder.addAction(0, getString(R.string.pause_all), servicePendingIntent(ACTION_PAUSE_ALL, 11))
        } else {
            builder.addAction(0, getString(R.string.resume_all), servicePendingIntent(ACTION_RESUME_ALL, 12))
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (foregroundShown) return
        foregroundShown = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (typedError: Exception) {
            Log.e("AriaService", "startForeground(dataSync) failed, retrying untyped", typedError)
            try {
                // Some Android 14+ builds refuse the dataSync type for sideloaded apps; a plain
                // foreground notification still lets the download run.
                startForeground(FOREGROUND_ID, notification)
                return
            } catch (e: Exception) {
                logFatalForeground(e)
                return
            }
        }
        return
    }

    private fun logFatalForeground(e: Exception) {
        // If we stay alive after a failed startForeground() the system kills the whole app
        // with "did not start in time". Stopping ourselves keeps the queue safe on disk and
        // the app usable; the next start retries.
        Log.e("AriaService", "startForeground failed", e)
        foregroundShown = false
        runCatching { stopSelf() }
    }

    private fun updateProgressNotification(force: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastNotificationAt < 1000) return
        lastNotificationAt = now
        val notification = buildProgressNotification()
        if (!foregroundShown) {
            startForegroundCompat(notification)
            return
        }
        safeNotify(FOREGROUND_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
            .onFailure { error ->
                // Do not silently lose notification failures, but avoid writing a log entry
                // every second when the user has blocked notifications at system level.
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastNotificationErrorAt > 60_000) {
                    lastNotificationErrorAt = now
                    Log.e("AriaService", "cannot post notification $id", error)
                }
            }
    }

    private fun canPostNotifications(): Boolean =
        NotificationSupport.canPostResultNotifications(this)

    private fun notifyCompleted(id: Long) {
        if (!settings.notificationsEnabled) return
        if (!canPostNotifications()) return
        val item = repo.item(id) ?: return
        val openIntent = runCatching { FileSaver.openIntent(this, item) }.getOrNull()
        val pending = if (openIntent != null) {
            PendingIntent.getActivity(
                this, 20_000 + item.id.toInt(), openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else mainPendingIntent()

        val bigText = getString(R.string.file_saved_to, "Download/" + FileSaver.SUB_FOLDER) +
            (item.warning?.let { "\n" + it } ?: "")
        val notification = NotificationCompat.Builder(this, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText(getString(R.string.download_finished) + " • " + Formats.bytes(item.downloadedBytes))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(30_000 + item.id.toInt(), notification)
    }

    private fun notifyFailed(id: Long) {
        if (!settings.notificationsEnabled) return
        if (!canPostNotifications()) return
        val item = repo.item(id) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(item.fileName)
            .setContentText(item.errorMessage ?: getString(R.string.download_failed))
            .setContentIntent(mainPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(40_000 + item.id.toInt(), notification)
    }

    // ================================================================== lifecycle

    private fun maybeStopIfIdle() {
        if (jobs.isNotEmpty()) return
        val list = repo.items.value
        val queued = list.any { it.status == DownloadStatus.QUEUED }
        val autoPaused = list.any { it.status == DownloadStatus.PAUSED && !it.pausedByUser }
        val retryPending = settings.autoRetry && list.any {
            it.status == DownloadStatus.FAILED && it.attempts in 1 until 3 && !it.pausedByUser
        }
        if (queued || retryPending) {
            scheduleIdleStop()
            return
        }
        if (autoPaused && (settings.wifiOnly && !onWifi || !networkAvailable)) {
            scheduleIdleStop()
            return
        }
        idleStopJob?.cancel()
        stopForegroundCompat()
        stopSelf()
    }

    private fun scheduleIdleStop() {
        if (idleStopJob?.isActive == true) return
        idleStopJob = scope.launch {
            delay(15 * 60 * 1000L)
            if (jobs.isEmpty() && repo.items.value.none { it.status == DownloadStatus.QUEUED }) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }
}
