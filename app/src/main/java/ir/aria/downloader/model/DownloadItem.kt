package ir.aria.downloader.model

import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.ExtractorEngine
import org.json.JSONObject

enum class DownloadStatus {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING
    val isFinished: Boolean get() = this == COMPLETED
}

/**
 * A single download task. Persisted as JSON so that the queue survives process death.
 * [fileUri] is either a MediaStore content:// uri (API 29+) or an absolute path (API < 29).
 */
data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String = "application/octet-stream",
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val speedBps: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
    val supportsResume: Boolean = true,
    val attempts: Int = 0,
    val pausedByUser: Boolean = false,
    val fileUri: String? = null,
    // ---- v2 (media engine) fields; optional so old queues keep loading ----
    val engine: EngineKind = EngineKind.HTTP,
    val preset: String = "best",
    val formatId: String? = null,
    val audioFormatId: String? = null,
    val outputExt: String? = null,
    val outputKind: String = "",
    val audioBitrateKbps: Int = 0,
    val selectionLabel: String? = null,
    val title: String? = null,
    val extractor: String? = null,
    val warning: String? = null,
    val height: Int = 0,
    val durationSec: Long? = null
) {
    /** Returns null for legacy queue entries, which retain their old preset behavior. */
    fun exactSelection(): ExtractorEngine.FormatSelection? {
        val primary = formatId?.takeIf { it.isNotBlank() } ?: return null
        if (outputKind.isBlank()) return null
        return ExtractorEngine.FormatSelection(
            formatId = primary,
            audioFormatId = audioFormatId,
            outputExt = outputExt,
            outputKind = outputKind,
            audioBitrateKbps = audioBitrateKbps,
            label = selectionLabel.orEmpty()
        )
    }

    val progressPercent: Int
        get() = when {
            status == DownloadStatus.COMPLETED -> 100
            totalBytes > 0 -> ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            else -> 0
        }

    val isIndeterminate: Boolean get() = totalBytes <= 0 && status != DownloadStatus.COMPLETED

    val isMedia: Boolean get() = engine != EngineKind.HTTP

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("url", url)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("totalBytes", totalBytes)
        put("downloadedBytes", downloadedBytes)
        put("status", status.name)
        put("createdAt", createdAt)
        put("completedAt", completedAt ?: -1L)
        put("errorMessage", errorMessage ?: JSONObject.NULL)
        put("etag", etag ?: JSONObject.NULL)
        put("lastModified", lastModified ?: JSONObject.NULL)
        put("supportsResume", supportsResume)
        put("attempts", attempts)
        put("pausedByUser", pausedByUser)
        put("fileUri", fileUri ?: JSONObject.NULL)
        put("engine", engine.name)
        put("preset", preset)
        put("formatId", formatId ?: JSONObject.NULL)
        put("audioFormatId", audioFormatId ?: JSONObject.NULL)
        put("outputExt", outputExt ?: JSONObject.NULL)
        put("outputKind", outputKind)
        put("audioBitrateKbps", audioBitrateKbps)
        put("selectionLabel", selectionLabel ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("extractor", extractor ?: JSONObject.NULL)
        put("warning", warning ?: JSONObject.NULL)
        put("height", height)
        put("durationSec", durationSec ?: -1L)
    }

    companion object {
        private fun JSONObject.stringOrNull(key: String): String? =
            optString(key, "").takeIf { it.isNotBlank() && it != "null" }

        fun fromJson(o: JSONObject): DownloadItem = DownloadItem(
            id = o.optLong("id"),
            url = o.optString("url"),
            fileName = o.optString("fileName"),
            mimeType = o.optString("mimeType", "application/octet-stream"),
            totalBytes = o.optLong("totalBytes", -1L),
            downloadedBytes = o.optLong("downloadedBytes", 0L),
            status = runCatching { DownloadStatus.valueOf(o.optString("status", "QUEUED")) }
                .getOrDefault(DownloadStatus.QUEUED),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            completedAt = o.optLong("completedAt", -1L).takeIf { it > 0 },
            errorMessage = o.stringOrNull("errorMessage"),
            etag = o.stringOrNull("etag"),
            lastModified = o.stringOrNull("lastModified"),
            supportsResume = o.optBoolean("supportsResume", true),
            attempts = o.optInt("attempts", 0),
            pausedByUser = o.optBoolean("pausedByUser", false),
            fileUri = o.stringOrNull("fileUri"),
            engine = EngineKind.fromName(o.optString("engine", "HTTP")),
            preset = o.optString("preset", "best"),
            formatId = o.stringOrNull("formatId"),
            audioFormatId = o.stringOrNull("audioFormatId"),
            outputExt = o.stringOrNull("outputExt"),
            outputKind = o.optString("outputKind", ""),
            audioBitrateKbps = o.optInt("audioBitrateKbps", 0),
            selectionLabel = o.stringOrNull("selectionLabel"),
            title = o.stringOrNull("title"),
            extractor = o.stringOrNull("extractor"),
            warning = o.stringOrNull("warning"),
            height = o.optInt("height", 0),
            durationSec = o.optLong("durationSec", -1L).takeIf { it > 0 }
        )
    }
}
