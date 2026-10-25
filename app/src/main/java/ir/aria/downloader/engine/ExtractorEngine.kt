package ir.aria.downloader.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import ir.aria.downloader.data.Settings
import ir.aria.downloader.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kotlin <-> Python bridge around `aria_dl.py` (yt-dlp) running inside the app through
 * Chaquopy. Every call blocks, so everything is confined to [Dispatchers.IO].
 */
class ExtractorEngine private constructor(
    private val appContext: Context,
    private val settings: Settings
) : ExtractorApi {

    // ------------------------------------------------------------------ models

    data class FormatRow(
        val id: String,
        val ext: String,
        val height: Int,
        val fps: Double?,
        val filesize: Long?,
        val audioOnly: Boolean,
        val progressive: Boolean,
        val note: String,
        val width: Int = 0,
        val videoOnly: Boolean = !audioOnly && !progressive,
        val hasAudio: Boolean = audioOnly || progressive,
        val hasVideo: Boolean = !audioOnly,
        val abr: Double? = null,
        val vbr: Double? = null,
        val tbr: Double? = null,
        val sizeApprox: Boolean = false,
        val vcodec: String? = null,
        val acodec: String? = null,
        val language: String = "",
        val dynamicRange: String = "",
        val sampleRate: Int = 0,
        val audioChannels: Int = 0
    )

    /** Exact user choice persisted with one queue item, never a global quality preset. */
    data class FormatSelection(
        val formatId: String,
        val audioFormatId: String? = null,
        val outputExt: String? = null,
        val outputKind: String = "video", // video | audio | video_only
        val audioBitrateKbps: Int = 0,
        val label: String = ""
    )

    data class PlaylistEntry(val title: String, val url: String, val durationSec: Long?)

    data class Probe(
        val ok: Boolean,
        val error: String? = null,
        val title: String = "",
        val uploader: String = "",
        val durationSec: Long? = null,
        val thumbnail: String? = null,
        val extractor: String = "",
        val isPlaylist: Boolean = false,
        val entries: List<PlaylistEntry> = emptyList(),
        val formats: List<FormatRow> = emptyList(),
        val bestHeight: Int = 0,
        val hasAudioOnly: Boolean = false,
        /** Direct image file for image-only posts (Pinterest pins, Reddit/TikTok photos). */
        val directFileUrl: String? = null
    )

    data class Plan(
        val ok: Boolean,
        val error: String? = null,
        val mode: String = "single",        // single | merge
        val formatId: String? = null,
        val videoId: String? = null,
        val audioId: String? = null,
        val height: Int = 0,
        val fps: Double? = null,
        val ext: String = "mp4",
        val filesize: Long = -1L,
        val title: String = "",
        val uploader: String = "",
        val durationSec: Long? = null,
        val thumbnail: String? = null,
        val extractor: String = "",
        val warning: String? = null,
        val audioOnly: Boolean = false,
        val sourceExt: String = ext,
        val outputKind: String = if (audioOnly) "audio" else "video",
        val postprocess: String = if (mode == "merge") "merge" else "none",
        val audioBitrateKbps: Int = 0,
        val note: String = ""
    )

    data class MediaFile(val path: String, val size: Long)

    data class Outcome(
        val ok: Boolean,
        val cancelled: Boolean = false,
        val error: String? = null,
        val title: String = "",
        val files: List<MediaFile> = emptyList(),
        val extractor: String = ""
    )

    data class EngineInfo(val engine: String, val ytDlp: String, val python: String)

    data class SearchHit(val title: String, val url: String, val durationSec: Long?, val uploader: String)

    /** Search failures must not be disguised as "song not found". */
    data class SearchResponse(
        val hits: List<SearchHit>,
        val error: String? = null
    )

    /** Progress event emitted by the python engine. */
    data class Progress(
        val downloaded: Long,
        val total: Long,
        val speed: Long,
        val percent: Double
    )

    // ------------------------------------------------------------------ lifecycle

    private var module: PyObject? = null

    companion object {
        @Volatile
        private var instance: ExtractorEngine? = null

        fun get(context: Context): ExtractorEngine =
            instance ?: synchronized(this) {
                instance ?: ExtractorEngine(
                    context.applicationContext,
                    Settings(context.applicationContext)
                ).also { instance = it }
            }

        @JvmStatic
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }

    private fun module(): PyObject {
        module?.let { return it }
        synchronized(this) {
            module?.let { return it }
            val startedAt = System.currentTimeMillis()
            DiagLog.i("Engine", "starting python runtime (Chaquopy)")
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            DiagLog.d("Engine", "python started, loading aria_dl")
            val loaded = Python.getInstance().getModule("aria_dl")
            module = loaded
            if (DiagLog.enabled) {
                // Python then writes its own yt-dlp lines into the very same session file.
                runCatching { loaded.callAttr("configure_diag", DiagLog.logFilePath() ?: "") }
                    .onFailure { DiagLog.w("Engine", "configure_diag failed", it) }
            }
            DiagLog.i(
                "Engine",
                "aria_dl loaded in " + (System.currentTimeMillis() - startedAt) +
                    "ms, diagPath=" + DiagLog.logFilePath()
            )
            return loaded
        }
    }

    /**
     * Boots the Python runtime and reads the engine version. Any failure is returned as a
     * readable Persian message instead of being thrown, so the UI can show it.
     */
    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        DiagLog.i("Engine", "selfTest requested")
        try {
            val raw = module().callAttr("engine_info").toString()
            DiagLog.i("Engine", "selfTest ok: $raw")
            val json = JSONObject(raw)
            "موتور سالم است.\n" +
                "موتور: " + json.optString("engine", "?") + "\n" +
                "yt-dlp: " + json.optString("yt_dlp", "?") + "\n" +
                "پایتون: " + json.optString("python", "?")
        } catch (t: Throwable) {
            DiagLog.e("Engine", "selfTest failed", t)
            "موتور اجرا نشد: " + t.javaClass.simpleName + "\n" + (t.message ?: "")
        }
    }

    private fun optionsJson(): String {
        val obj = JSONObject()
        obj.put("cookies_path", cookiesFile().absolutePath.takeIf { cookiesFile().exists() } ?: "")
        obj.put("user_agent", settings.mediaUserAgentOverride)
        obj.put("fragment_concurrency", settings.fragmentConcurrency)
        obj.put("proxy", settings.proxyUrl.trim())
        if (DiagLog.enabled) {
            obj.put("diag", true)
            obj.put("diag_log_path", DiagLog.logFilePath() ?: "")
        }
        return obj.toString()
    }

    /** One-line, secret-free summary of the settings every engine call runs with. */
    private fun callContext(): String =
        "ck=" + hasCookies() +
            " proxy=" + DiagLog.redactProxy(settings.proxyUrl) +
            " fragments=" + settings.fragmentConcurrency +
            " customUA=" + settings.mediaUserAgentOverride.isNotEmpty()

    fun cookiesFile(): File = File(appContext.filesDir, "cookies.txt")

    override fun hasCookies(): Boolean = cookiesFile().let { it.exists() && it.length() > 0 }

    // ------------------------------------------------------------------ calls

    override suspend fun info(): EngineInfo = withContext(Dispatchers.IO) {
        try {
            val raw = module().callAttr("engine_info").toString()
            DiagLog.i("Engine", "info: $raw")
            val json = JSONObject(raw)
            EngineInfo(
                engine = json.optString("engine", "5.4.0"),
                ytDlp = json.optString("yt_dlp", "?"),
                python = json.optString("python", "?")
            )
        } catch (e: Throwable) {
            DiagLog.e("Engine", "info failed", e)
            EngineInfo("5.4.0", "unavailable", e.javaClass.simpleName)
        }
    }

    override suspend fun probe(url: String): Probe = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        DiagLog.i("Engine", "probe start url=" + DiagLog.redactUrl(url) + " [" + callContext() + "]")
        try {
            val raw = module().callAttr("probe", url, optionsJson()).toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                val error = json.optString("error", "استخراج ناموفق بود")
                DiagLog.w(
                    "Engine",
                    "probe FAILED in " + (System.currentTimeMillis() - startedAt) + "ms url=" +
                        DiagLog.redactUrl(url) + " error='" + error + "'"
                )
                return@withContext Probe(false, error)
            }
            val entries = ArrayList<PlaylistEntry>()
            json.optJSONArray("entries")?.let { array ->
                for (i in 0 until array.length()) {
                    val e = array.optJSONObject(i) ?: continue
                    val entryUrl = e.optString("url")
                    if (entryUrl.isBlank()) continue
                    entries.add(
                        PlaylistEntry(
                            title = e.optString("title", "بدون عنوان"),
                            url = entryUrl,
                            durationSec = e.optLong("duration", -1L).takeIf { it > 0 }
                        )
                    )
                }
            }
            val formats = ArrayList<FormatRow>()
            json.optJSONArray("formats")?.let { array ->
                for (i in 0 until array.length()) {
                    val f = array.optJSONObject(i) ?: continue
                    formats.add(
                        FormatRow(
                            id = f.optString("id"),
                            ext = f.optString("ext"),
                            height = f.optInt("height", 0),
                            fps = f.optDouble("fps", 0.0).takeIf { !it.isNaN() && it > 0 },
                            filesize = f.optLong("filesize", -1L).takeIf { it > 0 },
                            audioOnly = f.optBoolean("audio_only"),
                            progressive = f.optBoolean("progressive"),
                            note = f.optString("note"),
                            width = f.optInt("width", 0),
                            videoOnly = f.optBoolean("video_only"),
                            hasAudio = f.optBoolean("has_audio"),
                            hasVideo = f.optBoolean("has_video"),
                            abr = f.optDouble("abr", 0.0).takeIf { !it.isNaN() && it > 0 },
                            vbr = f.optDouble("vbr", 0.0).takeIf { !it.isNaN() && it > 0 },
                            tbr = f.optDouble("tbr", 0.0).takeIf { !it.isNaN() && it > 0 },
                            sizeApprox = f.optBoolean("size_approx"),
                            vcodec = f.optString("vcodec").takeIf { it.isNotBlank() && it != "null" },
                            acodec = f.optString("acodec").takeIf { it.isNotBlank() && it != "null" },
                            language = f.optString("language"),
                            dynamicRange = f.optString("dynamic_range"),
                            sampleRate = f.optInt("asr", 0),
                            audioChannels = f.optInt("audio_channels", 0)
                        )
                    )
                }
            }
            DiagLog.i(
                "Engine",
                "probe ok in " + (System.currentTimeMillis() - startedAt) + "ms extractor=" +
                    json.optString("extractor") + " title='" + json.optString("title") +
                    "' playlist=" + json.optBoolean("is_playlist") +
                    " entries=" + entries.size + " formats=" + formats.size +
                    " bestHeight=" + json.optInt("best_height", 0) +
                    " directFile=" + DiagLog.redactUrl(json.optString("direct_file_url"))
            )
            if (DiagLog.enabled && entries.isNotEmpty()) {
                entries.take(30).forEachIndexed { index, entry ->
                    DiagLog.d(
                        "Engine",
                        " probe entry[" + index + "] '" + entry.title +
                            "' dur=" + entry.durationSec + " url=" + DiagLog.redactUrl(entry.url)
                    )
                }
                if (entries.size > 30) DiagLog.d("Engine", " probe entries truncated: " + entries.size + " total")
            }
            if (DiagLog.enabled) {
                formats.forEach { f ->
                    DiagLog.d(
                        "Engine",
                        " probe fmt id=" + f.id + " ext=" + f.ext + " " + f.width + "x" + f.height +
                            " fps=" + f.fps + " size=" + f.filesize + (if (f.sizeApprox) "~" else "") +
                            " audioOnly=" + f.audioOnly + " prog=" + f.progressive +
                            " vOnly=" + f.videoOnly + " vcodec=" + f.vcodec + " acodec=" + f.acodec +
                            " abr=" + f.abr + " vbr=" + f.vbr + " tbr=" + f.tbr + " note='" + f.note + "'"
                    )
                }
            }
            Probe(
                ok = true,
                title = json.optString("title"),
                uploader = json.optString("uploader"),
                durationSec = json.optLong("duration", -1L).takeIf { it > 0 },
                thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
                extractor = json.optString("extractor"),
                isPlaylist = json.optBoolean("is_playlist"),
                entries = entries,
                formats = formats,
                bestHeight = json.optInt("best_height", 0),
                hasAudioOnly = json.optBoolean("has_audio_only"),
                directFileUrl = json.optString("direct_file_url")
                    .takeIf { it.isNotBlank() && it != "null" }
            )
        } catch (e: Throwable) {
            DiagLog.e(
                "Engine",
                "probe EXCEPTION in " + (System.currentTimeMillis() - startedAt) + "ms url=" +
                    DiagLog.redactUrl(url),
                e
            )
            Probe(false, friendly(e))
        }
    }

    override suspend fun resolve(url: String, preset: String): Plan = resolve(url, preset, null)

    override suspend fun resolve(
        url: String,
        preset: String,
        selection: FormatSelection?
    ): Plan = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        DiagLog.i(
            "Engine",
            "resolve start url=" + DiagLog.redactUrl(url) + " preset=" + preset +
                " selection=" + (selection?.let {
                    "fmt=" + it.formatId + " afmt=" + it.audioFormatId + " out=" + it.outputKind +
                        "/" + it.outputExt + " abr=" + it.audioBitrateKbps + " label=[" + it.label + "]"
                } ?: "legacy") + " [" + callContext() + "]"
        )
        try {
            val options = JSONObject(optionsJson()).put("preset", preset)
            selection?.let { chosen ->
                options.put("selected_format_id", chosen.formatId)
                options.put("selected_audio_id", chosen.audioFormatId ?: "")
                options.put("output_ext", chosen.outputExt ?: "")
                options.put("output_kind", chosen.outputKind)
                options.put("audio_bitrate", chosen.audioBitrateKbps)
                options.put("selection_label", chosen.label)
            }
            val raw = module().callAttr("resolve", url, options.toString()).toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                val error = json.optString("error", "فرمت قابل دانلودی پیدا نشد")
                DiagLog.w(
                    "Engine",
                    "resolve FAILED in " + (System.currentTimeMillis() - startedAt) + "ms url=" +
                        DiagLog.redactUrl(url) + " error='" + error + "'"
                )
                return@withContext Plan(false, error = error)
            }
            DiagLog.i(
                "Engine",
                "resolve ok in " + (System.currentTimeMillis() - startedAt) + "ms extractor=" +
                    json.optString("extractor") + " mode=" + json.optString("mode", "single") +
                    " fmt=" + json.optString("format_id") + " v=" + json.optString("video_id") +
                    " a=" + json.optString("audio_id") + " ext=" + json.optString("ext") +
                    " sourceExt=" + json.optString("source_ext") +
                    " height=" + json.optInt("height", 0) + " size=" + json.optLong("filesize", -1L) +
                    " audioOnly=" + json.optBoolean("audio_only") +
                    " output=" + json.optString("output_kind") + " postprocess=" + json.optString("postprocess") +
                    " warning='" + json.optString("warning") + "' title='" + json.optString("title") + "'"
            )
            Plan(
                ok = true,
                mode = json.optString("mode", "single"),
                formatId = json.optString("format_id").takeIf { it.isNotBlank() && it != "null" },
                videoId = json.optString("video_id").takeIf { it.isNotBlank() && it != "null" },
                audioId = json.optString("audio_id").takeIf { it.isNotBlank() && it != "null" },
                height = json.optInt("height", 0),
                fps = json.optDouble("fps", 0.0).takeIf { !it.isNaN() && it > 0 },
                ext = json.optString("ext", "mp4").ifBlank { "mp4" },
                filesize = json.optLong("filesize", -1L),
                title = json.optString("title"),
                uploader = json.optString("uploader"),
                durationSec = json.optLong("duration", -1L).takeIf { it > 0 },
                thumbnail = json.optString("thumbnail").takeIf { it.isNotBlank() && it != "null" },
                extractor = json.optString("extractor"),
                warning = json.optString("warning").takeIf { it.isNotBlank() && it != "null" },
                audioOnly = json.optBoolean("audio_only"),
                sourceExt = json.optString("source_ext", json.optString("ext", "mp4")),
                outputKind = json.optString("output_kind", "video"),
                postprocess = json.optString("postprocess", "none"),
                audioBitrateKbps = json.optInt("audio_bitrate", 0),
                note = json.optString("note")
            )
        } catch (e: Throwable) {
            DiagLog.e(
                "Engine",
                "resolve EXCEPTION in " + (System.currentTimeMillis() - startedAt) + "ms url=" +
                    DiagLog.redactUrl(url),
                e
            )
            Plan(false, error = friendly(e))
        }
    }

    override suspend fun search(query: String, limit: Int): SearchResponse = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        DiagLog.i("Engine", "search start query='" + query + "' limit=" + limit + " [" + callContext() + "]")
        try {
            val raw = module().callAttr("search", query, limit, optionsJson()).toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                val error = json.optString("error", "جست‌وجوی منبع صوتی ناموفق بود")
                DiagLog.w("Engine", "search FAILED query='" + query + "' error='" + error + "'")
                return@withContext SearchResponse(emptyList(), error)
            }
            val array: JSONArray = json.optJSONArray("entries") ?: JSONArray()
            val hits = ArrayList<SearchHit>()
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                val url = e.optString("url")
                if (url.isBlank()) continue
                hits.add(
                    SearchHit(
                        title = e.optString("title"),
                        url = url,
                        durationSec = e.optLong("duration", -1L).takeIf { it > 0 },
                        uploader = e.optString("uploader")
                    )
                )
            }
            DiagLog.i(
                "Engine",
                "search ok in " + (System.currentTimeMillis() - startedAt) + "ms hits=" + hits.size
            )
            if (DiagLog.enabled) {
                hits.forEachIndexed { index, hit ->
                    DiagLog.d(
                        "Engine",
                        " hit[" + index + "] '" + hit.title + "' by '" + hit.uploader +
                            "' dur=" + hit.durationSec + " url=" + DiagLog.redactUrl(hit.url)
                    )
                }
            }
            SearchResponse(hits)
        } catch (e: Throwable) {
            DiagLog.e("Engine", "search EXCEPTION query='" + query + "'", e)
            SearchResponse(emptyList(), friendly(e))
        }
    }

    /**
     * Downloads one rendition. [formatId] null means "let the preset decide".
     * [shouldCancel] is polled from the python progress hook, so pausing is immediate.
     */
    override suspend fun download(
        url: String,
        outDir: File,
        formatId: String?,
        preset: String,
        shouldCancel: () -> Boolean,
        onProgress: (Progress) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        DiagLog.i(
            "Engine",
            "download start url=" + DiagLog.redactUrl(url) + " formatId=" + formatId +
                " preset=" + preset + " outDir=" + outDir.absolutePath + " [" + callContext() + "]"
        )
        try {
            outDir.mkdirs()
            val request = JSONObject()
                .put("url", url)
                .put("out_dir", outDir.absolutePath)
                .put("format_id", formatId ?: "")
                .put("preset", preset)
                .put("cookies_path", cookiesFile().absolutePath.takeIf { cookiesFile().exists() } ?: "")
                .put("user_agent", settings.mediaUserAgentOverride)
                .put("fragment_concurrency", settings.fragmentConcurrency)
                .put("proxy", settings.proxyUrl.trim())
            if (DiagLog.enabled) {
                request.put("diag", true)
                request.put("diag_log_path", DiagLog.logFilePath() ?: "")
            }
            val milestones = DiagLog.ProgressMilestones("Engine", "yt-dlp fmt=" + formatId)

            val listener = object {
                @Suppress("unused")
                fun on_event(json: String) {
                    try {
                        val event = JSONObject(json)
                        when (event.optString("type")) {
                            "log" -> DiagLog.i("Engine/py", event.optString("message"))
                            "error" -> DiagLog.w("Engine/py", "error event: " + event.optString("message"))
                            "cancelled" -> DiagLog.i("Engine/py", "cancelled event")
                            "progress" -> {
                                val total = event.optLong("total", -1L)
                                val done = event.optLong("downloaded", 0L)
                                milestones.onProgress(done, total, event.optLong("speed", 0L))
                                onProgress(
                                    Progress(
                                        downloaded = done,
                                        total = total,
                                        speed = event.optLong("speed", 0L),
                                        percent = if (total > 0) done * 100.0 / total else -1.0
                                    )
                                )
                            }
                            "done" -> {
                                DiagLog.i(
                                    "Engine/py",
                                    "done event title='" + event.optString("title") +
                                        "' files=" + event.optJSONArray("files")
                                )
                                val f = event.optJSONArray("files")
                                if (f != null) {
                                    var size = 0L
                                    for (i in 0 until f.length()) {
                                        size += f.optJSONObject(i)?.optLong("size", 0L) ?: 0L
                                    }
                                    onProgress(Progress(size, size, 0, 100.0))
                                }
                            }
                        }
                    } catch (ignored: Exception) {
                        DiagLog.w("Engine", "bad python event ignored: " + json.take(200), ignored)
                    }
                }

                @Suppress("unused")
                fun should_cancel(): Boolean = try {
                    shouldCancel()
                } catch (e: Exception) {
                    false
                }
            }

            val raw = module().callAttr("download", request.toString(), listener).toString()
            val json = JSONObject(raw)
            val cancelled = json.optBoolean("cancelled")
            if (!json.optBoolean("ok")) {
                val error = json.optString("error", "دانلود ناموفق بود")
                DiagLog.w(
                    "Engine",
                    "download " + (if (cancelled) "CANCELLED" else "FAILED") + " in " +
                        (System.currentTimeMillis() - startedAt) + "ms fmt=" + formatId +
                        " error='" + error + "' detail='" + json.optString("detail") + "'"
                )
                return@withContext Outcome(ok = false, cancelled = cancelled, error = error)
            }
            val files = ArrayList<MediaFile>()
            json.optJSONArray("files")?.let { array ->
                for (i in 0 until array.length()) {
                    val f = array.optJSONObject(i) ?: continue
                    val path = f.optString("path")
                    if (path.isBlank()) continue
                    files.add(MediaFile(path, f.optLong("size", 0L)))
                }
            }
            DiagLog.i(
                "Engine",
                "download ok in " + (System.currentTimeMillis() - startedAt) + "ms fmt=" + formatId +
                    " extractor=" + json.optString("extractor") +
                    " title='" + json.optString("title") + "' files=" +
                    files.joinToString(", ") { it.path.substringAfterLast('/') + "(" + it.size + "B)" }
            )
            Outcome(
                ok = files.isNotEmpty(),
                error = if (files.isEmpty()) "فایل خروجی پیدا نشد" else null,
                title = json.optString("title"),
                files = files,
                extractor = json.optString("extractor")
            )
        } catch (e: Throwable) {
            DiagLog.e(
                "Engine",
                "download EXCEPTION in " + (System.currentTimeMillis() - startedAt) + "ms fmt=" +
                    formatId + " url=" + DiagLog.redactUrl(url),
                e
            )
            Outcome(false, error = friendly(e))
        }
    }

    private fun friendly(error: Throwable): String {
        val name = error.javaClass.simpleName
        val message = error.message?.take(200) ?: ""
        // NB: the full stack trace is already in the diag log; the user only sees this summary.
        return when {
            name.contains("PyException") -> "موتور استخراج خطا داد: $message"
            name.contains("ImportError") || name.contains("ModuleNotFound") ->
                "موتور استخراج در دسترس نیست (نصب ناقص است)"
            else -> "خطای موتور استخراج: $message"
        }
    }
}
