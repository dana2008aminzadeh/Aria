package ir.aria.downloader.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import ir.aria.downloader.data.Settings
import ir.aria.downloader.util.Logs
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
        val hasAudioOnly: Boolean = false
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
            Logs.d(appContext, "engine", "starting python runtime")
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(appContext))
            }
            Logs.d(appContext, "engine", "python started, loading aria_dl")
            val loaded = Python.getInstance().getModule("aria_dl")
            module = loaded
            Logs.d(appContext, "engine", "aria_dl loaded")
            return loaded
        }
    }

    /**
     * Boots the Python runtime and reads the engine version. Any failure is returned as a
     * readable Persian message instead of being thrown, so the UI can show it.
     */
    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        try {
            val raw = module().callAttr("engine_info").toString()
            Logs.d(appContext, "engine", "selfTest ok: $raw")
            val json = JSONObject(raw)
            "موتور سالم است.\n" +
                "موتور: " + json.optString("engine", "?") + "\n" +
                "yt-dlp: " + json.optString("yt_dlp", "?") + "\n" +
                "پایتون: " + json.optString("python", "?")
        } catch (t: Throwable) {
            Logs.e(appContext, "engine", "selfTest failed", t)
            "موتور اجرا نشد: " + t.javaClass.simpleName + "\n" + (t.message ?: "")
        }
    }

    private fun optionsJson(): String {
        val obj = JSONObject()
        obj.put("cookies_path", cookiesFile().absolutePath.takeIf { cookiesFile().exists() } ?: "")
        obj.put("user_agent", settings.userAgent)
        obj.put("fragment_concurrency", settings.fragmentConcurrency)
        obj.put("proxy", settings.proxyUrl.trim())
        return obj.toString()
    }

    fun cookiesFile(): File = File(appContext.filesDir, "cookies.txt")

    override fun hasCookies(): Boolean = cookiesFile().let { it.exists() && it.length() > 0 }

    // ------------------------------------------------------------------ calls

    override suspend fun info(): EngineInfo = withContext(Dispatchers.IO) {
        try {
            val raw = module().callAttr("engine_info").toString()
            val json = JSONObject(raw)
            EngineInfo(
                engine = json.optString("engine", "2.2.0"),
                ytDlp = json.optString("yt_dlp", "?"),
                python = json.optString("python", "?")
            )
        } catch (e: Throwable) {
            EngineInfo("2.2.0", "unavailable", e.javaClass.simpleName)
        }
    }

    override suspend fun probe(url: String): Probe = withContext(Dispatchers.IO) {
        try {
            val raw = module().callAttr("probe", url, optionsJson()).toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) {
                return@withContext Probe(false, json.optString("error", "استخراج ناموفق بود"))
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
                hasAudioOnly = json.optBoolean("has_audio_only")
            )
        } catch (e: Throwable) {
            Probe(false, friendly(e))
        }
    }

    override suspend fun resolve(url: String, preset: String): Plan = resolve(url, preset, null)

    override suspend fun resolve(
        url: String,
        preset: String,
        selection: FormatSelection?
    ): Plan = withContext(Dispatchers.IO) {
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
                return@withContext Plan(false, error = json.optString("error", "فرمت قابل دانلودی پیدا نشد"))
            }
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
            Plan(false, error = friendly(e))
        }
    }

    override suspend fun search(query: String, limit: Int): List<SearchHit> = withContext(Dispatchers.IO) {
        try {
            val raw = module().callAttr("search", query, limit, optionsJson()).toString()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok")) return@withContext emptyList()
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
            hits
        } catch (e: Throwable) {
            emptyList()
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
        try {
            outDir.mkdirs()
            val request = JSONObject()
                .put("url", url)
                .put("out_dir", outDir.absolutePath)
                .put("format_id", formatId ?: "")
                .put("preset", preset)
                .put("cookies_path", cookiesFile().absolutePath.takeIf { cookiesFile().exists() } ?: "")
                .put("user_agent", settings.userAgent)
                .put("fragment_concurrency", settings.fragmentConcurrency)
                .put("proxy", settings.proxyUrl.trim())

            val listener = object {
                @Suppress("unused")
                fun on_event(json: String) {
                    try {
                        val event = JSONObject(json)
                        when (event.optString("type")) {
                            "progress" -> {
                                val total = event.optLong("total", -1L)
                                val done = event.optLong("downloaded", 0L)
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
                return@withContext Outcome(
                    ok = false,
                    cancelled = cancelled,
                    error = json.optString("error", "دانلود ناموفق بود")
                )
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
            Outcome(
                ok = files.isNotEmpty(),
                error = if (files.isEmpty()) "فایل خروجی پیدا نشد" else null,
                title = json.optString("title"),
                files = files,
                extractor = json.optString("extractor")
            )
        } catch (e: Throwable) {
            Outcome(false, error = friendly(e))
        }
    }

    private fun friendly(error: Throwable): String {
        val name = error.javaClass.simpleName
        val message = error.message?.take(200) ?: ""
        return when {
            name.contains("PyException") -> "موتور استخراج خطا داد: $message"
            name.contains("ImportError") || name.contains("ModuleNotFound") ->
                "موتور استخراج در دسترس نیست (نصب ناقص است)"
            else -> "خطای موتور استخراج: $message"
        }
    }
}
