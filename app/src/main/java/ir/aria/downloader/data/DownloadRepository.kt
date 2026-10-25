package ir.aria.downloader.data

import android.content.Context
import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.engine.MediaUrlNormalizer
import ir.aria.downloader.engine.UrlClassifier
import ir.aria.downloader.diag.DiagLog
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.net.FileNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** A request to enqueue one download (produced by the link ingestor / add screen). */
data class NewDownload(
    val url: String,
    val fileName: String? = null,
    val engine: EngineKind = EngineKind.HTTP,
    val preset: String = "best",
    val mimeType: String = "application/octet-stream",
    val extractor: String? = null,
    val durationSec: Long? = null,
    val height: Int = 0,
    val selection: ExtractorEngine.FormatSelection? = null
)

/**
 * Single source of truth for the download queue. Everything the UI shows comes from
 * [items]; every mutation goes through [update] / [add] so persistence and observers
 * stay consistent.
 */
class DownloadRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    val settings = Settings(appContext)
    val store = DownloadStore(File(appContext.filesDir, "downloads.json"))
    val partsDir = File(appContext.filesDir, "parts").apply { mkdirs() }
    val mediaDir = File(appContext.filesDir, "media").apply { mkdirs() }

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private val writeLock = Any()
    private var lastPersist = 0L

    companion object {
        @Volatile
        private var instance: DownloadRepository? = null

        fun get(context: Context): DownloadRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadRepository(context).also {
                    it.load()
                    instance = it
                }
            }

        /** Test-only hook: forget the singleton so a fresh queue can be created. */
        @JvmStatic
        @androidx.annotation.VisibleForTesting
        fun resetForTests() {
            synchronized(this) { instance = null }
        }

        private val PLACEHOLDER = Regex("""^file-\d+\.bin$""")

        fun isPlaceholderName(name: String): Boolean = PLACEHOLDER.matches(name)
    }

    private fun load() {
        store.load()
        store.repairInterrupted()
        store.save()
        for (item in store.all()) {
            if (item.status == DownloadStatus.COMPLETED) partFile(item.id).delete()
        }
        emit()
        val all = store.all()
        DiagLog.i(
            "Queue",
            "loaded " + all.size + " item(s): " +
                DownloadStatus.values().joinToString(", ") { st ->
                    st.name + "=" + all.count { it.status == st }
                }
        )
    }

    fun item(id: Long): DownloadItem? = store.get(id)

    fun partFile(id: Long): File = File(partsDir, "$id.part")

    fun mediaWorkDir(id: Long): File = File(mediaDir, id.toString()).apply { mkdirs() }

    // ------------------------------------------------------------------ adding

    /** Adds plain urls, choosing the right engine for each one. */
    fun add(urls: List<String>): List<DownloadItem> {
        val requests = urls.map { rawUrl ->
            val url = MediaUrlNormalizer.normalize(rawUrl)
            val engine = UrlClassifier.classify(url)
            val name = FileNames.fromUrl(url)
            NewDownload(
                url = url,
                fileName = name,
                engine = if (engine == EngineKind.SPOTIFY) EngineKind.MEDIA else engine,
                preset = if (engine == EngineKind.SPOTIFY) "audio" else "best",
                mimeType = if (name != null) FileNames.guessMime(name) else "application/octet-stream"
            )
        }
        return addRequests(requests)
    }

    fun addRequests(requests: List<NewDownload>): List<DownloadItem> {
        val existingActive = store.all()
            .filter {
                it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING ||
                    it.status == DownloadStatus.PAUSED
            }
            .map { it.url }
            .toHashSet()
        val created = ArrayList<DownloadItem>()
        for (request in requests) {
            val url = MediaUrlNormalizer.normalize(request.url)
            if (url.isEmpty() || existingActive.contains(url)) continue
            val id = store.newId()
            val guessedFromUrl = request.fileName?.takeIf { it.isNotBlank() } ?: FileNames.fromUrl(url)
            val placeholder = "file-$id.bin"
            val name = guessedFromUrl ?: placeholder
            val mime = when {
                request.mimeType != "application/octet-stream" -> request.mimeType
                guessedFromUrl != null -> FileNames.guessMime(name)
                request.engine != EngineKind.HTTP &&
                    (request.preset == "audio" || request.selection?.outputKind == "audio") ->
                    request.selection?.outputExt?.takeIf { it.isNotBlank() }
                        ?.let { FileNames.guessMime("audio.$it") } ?: "audio/mp4"
                request.engine != EngineKind.HTTP -> "video/mp4"
                else -> "application/octet-stream"
            }
            val item = DownloadItem(
                id = id,
                url = url,
                fileName = name,
                mimeType = mime,
                status = DownloadStatus.QUEUED,
                createdAt = System.currentTimeMillis(),
                engine = request.engine,
                preset = request.preset,
                formatId = request.selection?.formatId,
                audioFormatId = request.selection?.audioFormatId,
                outputExt = request.selection?.outputExt,
                outputKind = request.selection?.outputKind.orEmpty(),
                audioBitrateKbps = request.selection?.audioBitrateKbps ?: 0,
                selectionLabel = request.selection?.label,
                title = request.fileName,
                extractor = request.extractor,
                height = request.height,
                durationSec = request.durationSec
            )
            store.upsert(item)
            existingActive.add(url)
            created.add(item)
            DiagLog.i(
                "Queue",
                "added #" + item.id + " engine=" + item.engine + " preset=" + item.preset +
                    " name='" + item.fileName + "' mime=" + item.mimeType +
                    " extractor=" + item.extractor +
                    " selection=[" + (item.selectionLabel ?: "") + "] fmt=" + item.formatId +
                    " afmt=" + item.audioFormatId + " out=" + item.outputKind + "/" + item.outputExt +
                    " url=" + DiagLog.redactUrl(item.url)
            )
        }
        if (DiagLog.enabled && created.size != requests.size) {
            DiagLog.w(
                "Queue",
                "addRequests: " + created.size + " of " + requests.size +
                    " queued (duplicates/empty urls skipped)"
            )
        }
        persist(force = true)
        emit()
        return created
    }

    // ------------------------------------------------------------------ mutating

    fun update(id: Long, persist: Boolean = false, transform: (DownloadItem) -> DownloadItem): DownloadItem? {
        val current = store.get(id) ?: return null
        val updated = transform(current)
        if (DiagLog.enabled && updated.status != current.status) {
            DiagLog.i(
                "Queue",
                "#" + id + " " + current.status + " -> " + updated.status +
                    " downloaded=" + updated.downloadedBytes + "/" + updated.totalBytes +
                    " attempts=" + updated.attempts + " pausedByUser=" + updated.pausedByUser +
                    (updated.errorMessage?.let { " error='$it'" } ?: "") +
                    (updated.warning?.let { " warning='$it'" } ?: "") +
                    " name='" + updated.fileName + "'"
            )
        }
        store.upsert(updated)
        emit()
        if (persist) persist(force = true) else persist()
        return updated
    }

    fun remove(id: Long): DownloadItem? {
        val before = store.get(id)
        DiagLog.i(
            "Queue",
            "remove #" + id + " status=" + before?.status + " name='" + before?.fileName + "'" +
                " url=" + DiagLog.redactUrl(before?.url)
        )
        val removed = store.remove(id)
        partFile(id).delete()
        mediaWorkDir(id).deleteRecursively()
        persist(force = true)
        emit()
        return removed
    }

    fun updateProgress(id: Long, downloaded: Long, total: Long, speedBps: Long) {
        val current = store.get(id) ?: return
        if (current.status != DownloadStatus.RUNNING && current.status != DownloadStatus.QUEUED) return
        val updated = current.copy(
            downloadedBytes = downloaded,
            totalBytes = if (total > 0) total else current.totalBytes,
            speedBps = speedBps,
            status = DownloadStatus.RUNNING
        )
        store.upsert(updated)
        emit()
        persist()
    }

    /** Renames the queue entry and, for finished downloads, the file inside Downloads/. */
    fun renameItem(id: Long, newName: String) {
        val current = store.get(id) ?: return
        val clean = FileNames.sanitize(newName, current.fileName)
        DiagLog.i("Queue", "rename #$id '${current.fileName}' -> '$clean'")
        if (clean == current.fileName) return
        if (current.status == DownloadStatus.COMPLETED) {
            val newUri = FileSaver.rename(appContext, current, clean)
            update(id, persist = true) {
                it.copy(fileName = clean, fileUri = newUri ?: it.fileUri)
            }
        } else {
            update(id, persist = true) { it.copy(fileName = clean) }
        }
    }

    private fun emit() {
        _items.value = store.all()
    }

    private fun persist(force: Boolean = false) {
        synchronized(writeLock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPersist < 2500) return
            lastPersist = now
            store.save()
        }
    }

    fun persistNow() = persist(force = true)

    // ------------------------------------------------------------------ aggregates

    data class Summary(
        val activeCount: Int,
        val queuedCount: Int,
        val pausedCount: Int,
        val failedCount: Int,
        val completedCount: Int,
        val totalDownloadedBytes: Long,
        val aggregateProgress: Int?,
        val aggregateSpeed: Long
    )

    fun summary(): Summary {
        val list = _items.value
        val active = list.filter { it.status == DownloadStatus.RUNNING }
        val known = active.filter { it.totalBytes > 0 }
        val sumDownloaded = active.sumOf { it.downloadedBytes }
        val sumTotal = known.sumOf { it.totalBytes }
        val aggregate = when {
            active.isEmpty() -> null
            known.size != active.size || sumTotal <= 0 -> null
            else -> ((sumDownloaded * 100) / sumTotal).toInt().coerceIn(0, 100)
        }
        val completed = list.filter { it.status == DownloadStatus.COMPLETED }
        return Summary(
            activeCount = active.size,
            queuedCount = list.count { it.status == DownloadStatus.QUEUED },
            pausedCount = list.count { it.status == DownloadStatus.PAUSED },
            failedCount = list.count { it.status == DownloadStatus.FAILED },
            completedCount = completed.size,
            totalDownloadedBytes = completed.sumOf { it.downloadedBytes },
            aggregateProgress = aggregate,
            aggregateSpeed = active.sumOf { it.speedBps }
        )
    }
}
