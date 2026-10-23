package ir.aria.downloader.engine

import ir.aria.downloader.util.Formats
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure, testable model behind the per-link format/output dialog.
 *
 * A [Choice] is one real extractor format. Separate video rows are paired with the
 * best compatible audio row, while [Output] describes a real container/conversion.
 */
object FormatOptions {

    data class Choice(
        val format: ExtractorEngine.FormatRow,
        val pairedAudio: ExtractorEngine.FormatRow?,
        val label: String,
        val detail: String
    )

    data class Output(
        val ext: String?,
        val kind: String,
        val bitrateKbps: Int,
        val label: String,
        val detail: String
    ) {
        fun selection(choice: Choice): ExtractorEngine.FormatSelection {
            val audioId = when (kind) {
                "video", "audio" -> choice.pairedAudio?.id
                else -> null
            }
            val choiceLabel = choice.label.substringBefore(" • ID:")
            return ExtractorEngine.FormatSelection(
                formatId = choice.format.id,
                audioFormatId = audioId,
                outputExt = ext,
                outputKind = kind,
                audioBitrateKbps = bitrateKbps,
                label = "$choiceLabel • ${label.substringBefore(" •")}"
            )
        }
    }

    fun choices(
        formats: List<ExtractorEngine.FormatRow>,
        audioOnly: Boolean = false
    ): List<Choice> {
        val audioRows = formats.filter { it.audioOnly }
        val usable = if (audioOnly) {
            // Spotify/search should stay audio-only and must not download video merely
            // because a progressive row sorts above the actual audio tracks.
            if (audioRows.isNotEmpty()) audioRows else formats.filter { it.hasAudio }
        } else {
            formats
        }
        return usable.map { row ->
            val pair = if (!audioOnly && row.videoOnly) bestAudioFor(row, audioRows) else null
            val label = choiceLabel(row, pair)
            Choice(row, pair, label, detailLabel(row, pair))
        }
    }

    fun outputs(choice: Choice, audioOnly: Boolean = false): List<Output> {
        val row = choice.format
        if (audioOnly || row.audioOnly) return audioOutputs(directOriginal = row.audioOnly)

        val result = ArrayList<Output>()
        if (row.progressive) {
            result.add(Output(null, "video", 0, "ویدیو + صدا • ${row.ext.uppercase()}",
                "فایل ترکیبی اصلی؛ بدون تبدیل"))
            addVideoContainers(result, choice, includeAutomatic = false)
            result.addAll(audioOutputs(directOriginal = false))
            return result
        }

        if (row.videoOnly) {
            if (choice.pairedAudio != null) {
                val container = defaultContainer(row, choice.pairedAudio)
                result.add(Output(container, "video", 0,
                    "ویدیو + صدا • ${container.uppercase()}",
                    "ادغام بدون تغییر کدک با ${codecName(choice.pairedAudio.acodec, true)}"))
                addVideoContainers(result, choice, includeAutomatic = true, skipExt = container)
                result.addAll(audioOutputs(directOriginal = false))
            }
            result.add(Output(null, "video_only", 0, "فقط تصویر • ${row.ext.uppercase()}",
                "بدون دانلود صدا"))
            return result
        }
        return result
    }

    private fun addVideoContainers(
        result: MutableList<Output>,
        choice: Choice,
        includeAutomatic: Boolean,
        skipExt: String? = null
    ) {
        val row = choice.format
        val audio = choice.pairedAudio
        val candidates = buildList {
            if (mp4Compatible(row, audio)) add("mp4")
            if (webmCompatible(row, audio)) add("webm")
            add("mkv")
        }.distinct()
        for (ext in candidates) {
            if (ext == skipExt || (!includeAutomatic && ext.equals(row.ext, true))) continue
            result.add(Output(ext, "video", 0, "ویدیو + صدا • ${ext.uppercase()}",
                "تغییر ظرف بدون re-encode ویدیو"))
        }
    }

    private fun audioOutputs(directOriginal: Boolean): List<Output> {
        val result = ArrayList<Output>()
        if (directOriginal) {
            result.add(Output(null, "audio", 0, "صدا • اصلی", "بدون تبدیل و بدون افت کیفیت"))
        }
        for (rate in listOf(128, 192, 256, 320)) {
            result.add(Output("mp3", "audio", rate, "MP3 • $rate kb/s", "تبدیل واقعی به MP3"))
        }
        for (rate in listOf(128, 192, 256, 320)) {
            result.add(Output("m4a", "audio", rate, "M4A • $rate kb/s", "AAC داخل M4A"))
        }
        for (rate in listOf(96, 128, 160, 192)) {
            result.add(Output("opus", "audio", rate, "Opus • $rate kb/s", "تبدیل واقعی به Opus"))
        }
        for (rate in listOf(128, 192, 256)) {
            result.add(Output("ogg", "audio", rate, "OGG • $rate kb/s", "Vorbis داخل OGG"))
        }
        result.add(Output("flac", "audio", 0, "FLAC • Lossless", "فشرده‌سازی بدون افت؛ منبع فشرده، کیفیت از دست‌رفته را برنمی‌گرداند"))
        result.add(Output("wav", "audio", 0, "WAV • PCM", "خروجی PCM با حجم زیاد"))
        return result
    }

    private fun bestAudioFor(
        video: ExtractorEngine.FormatRow,
        rows: List<ExtractorEngine.FormatRow>
    ): ExtractorEngine.FormatRow? {
        return rows.maxWithOrNull(compareBy<ExtractorEngine.FormatRow> {
            when (defaultContainer(video, it)) {
                "mp4" -> if (it.ext == "m4a" || it.acodec?.startsWith("mp4a") == true) 3 else 1
                "webm" -> if (it.ext == "webm" || it.acodec?.startsWith("opus") == true) 3 else 1
                else -> 1
            }
        }.thenBy { it.abr ?: it.tbr ?: 0.0 }
            .thenBy { if (it.filesize != null) 1 else 0 })
    }

    private fun choiceLabel(row: ExtractorEngine.FormatRow, audio: ExtractorEngine.FormatRow?): String {
        val quality = when {
            row.audioOnly -> row.abr?.let { "${it.roundToInt()} kb/s صدا" }
                ?: row.note.ifBlank { "صدا" }
            row.height > 0 -> "${row.height}p${row.fps?.let { if (it > 30) it.roundToInt().toString() else "" }.orEmpty()}"
            else -> row.note.ifBlank { row.id }
        }
        val codecs = when {
            row.audioOnly -> codecName(row.acodec, true)
            row.progressive -> "${codecName(row.vcodec)} + ${codecName(row.acodec, true)}"
            audio != null -> "${codecName(row.vcodec)} + ${codecName(audio.acodec, true)}"
            else -> "${codecName(row.vcodec)} • بدون صدا"
        }
        return "$quality • ${row.ext.uppercase()} • $codecs • ID:${row.id}"
    }

    private fun detailLabel(row: ExtractorEngine.FormatRow, audio: ExtractorEngine.FormatRow?): String {
        val parts = ArrayList<String>()
        parts.add("Format ID: ${row.id}")
        if (row.width > 0 && row.height > 0) parts.add("${row.width}×${row.height}")
        row.fps?.let { parts.add("${it.roundToInt()} fps") }
        row.tbr?.let { parts.add("TBR ${it.roundToInt()} kb/s") }
        row.abr?.let { parts.add("ABR ${it.roundToInt()} kb/s") }
        row.language.takeIf { it.isNotBlank() }?.let { parts.add("زبان: $it") }
        row.dynamicRange.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        row.note.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val size = (row.filesize ?: 0L) + (audio?.filesize ?: 0L)
        if (size > 0) {
            val approximate = row.sizeApprox || audio?.sizeApprox == true || row.filesize == null ||
                (row.videoOnly && audio?.filesize == null)
            parts.add((if (approximate) "≈" else "") + Formats.bytes(size))
        }
        audio?.let { parts.add("Audio ID: ${it.id} • ${it.ext.uppercase()} • ${it.abr?.roundToInt() ?: "?"} kb/s") }
        return parts.joinToString("\n")
    }

    private fun codecName(codec: String?, audio: Boolean = false): String {
        val value = codec?.lowercase(Locale.US).orEmpty()
        if (value.isBlank()) return if (audio) "صدا" else "ویدیو"
        return when {
            value.startsWith("avc") || value.startsWith("h264") -> "H.264"
            value.startsWith("hev") || value.startsWith("hvc") || value.startsWith("h265") -> "HEVC"
            value.startsWith("vp9") || value.startsWith("vp09") -> "VP9"
            value.startsWith("vp8") || value.startsWith("vp08") -> "VP8"
            value.startsWith("av1") || value.startsWith("av01") -> "AV1"
            value.startsWith("mp4a") || value.startsWith("aac") -> "AAC"
            value.startsWith("opus") -> "Opus"
            value.startsWith("vorbis") -> "Vorbis"
            value.startsWith("mp3") -> "MP3"
            else -> codec!!.substringBefore('.').uppercase(Locale.US)
        }
    }

    private fun starts(codec: String?, prefixes: List<String>, unknownAllowed: Boolean = true): Boolean {
        if (codec.isNullOrBlank()) return unknownAllowed
        val value = codec.lowercase(Locale.US)
        return prefixes.any { value.startsWith(it) }
    }

    private fun mp4Compatible(video: ExtractorEngine.FormatRow, audio: ExtractorEngine.FormatRow?): Boolean {
        if (!starts(video.vcodec, listOf("avc", "h264", "hev", "hvc", "h265", "vp09", "vp9", "av01", "av1", "mp4v", "mpeg4"))) return false
        return audio == null || starts(audio.acodec,
            listOf("mp4a", "aac", "ac-3", "ec-3", "alac", "mp3", "opus", "flac"))
    }

    private fun webmCompatible(video: ExtractorEngine.FormatRow, audio: ExtractorEngine.FormatRow?): Boolean {
        if (!starts(video.vcodec,
                listOf("vp8", "vp08", "vp9", "vp09", "av1", "av01"), unknownAllowed = false)
        ) return false
        return audio == null || starts(audio.acodec, listOf("opus", "vorbis"), unknownAllowed = false)
    }

    private fun defaultContainer(video: ExtractorEngine.FormatRow, audio: ExtractorEngine.FormatRow): String {
        if (webmCompatible(video, audio) &&
            (video.ext == "webm" || audio.ext in listOf("webm", "ogg", "opus"))
        ) return "webm"
        if (mp4Compatible(video, audio)) return "mp4"
        if (webmCompatible(video, audio)) return "webm"
        return "mkv"
    }
}
