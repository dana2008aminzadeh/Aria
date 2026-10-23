package ir.aria.downloader.net

import java.io.File

/** Pure JVM FFmpeg argument builder; no Android/native class is loaded by unit tests. */
object MediaCommands {
    fun merge(video: File, audio: File, output: File): List<String> {
        val args = arrayListOf(
            "-y", "-i", video.absolutePath, "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
            "-max_muxing_queue_size", "1024"
        )
        addFaststart(args, output)
        args.add(output.absolutePath)
        return args
    }

    fun remux(input: File, output: File, includeAudio: Boolean): List<String> {
        val args = arrayListOf("-y", "-i", input.absolutePath, "-map", "0:v:0")
        if (includeAudio) args.addAll(listOf("-map", "0:a?"))
        args.addAll(listOf("-c", "copy", "-max_muxing_queue_size", "1024"))
        addFaststart(args, output)
        args.add(output.absolutePath)
        return args
    }

    fun audio(input: File, output: File, ext: String, bitrateKbps: Int): List<String> {
        val extension = ext.lowercase().removePrefix(".")
        val codec = when (extension) {
            "mp3" -> "libmp3lame"
            "m4a", "aac", "mp4" -> "aac"
            "opus", "webm" -> "libopus"
            "ogg" -> "libvorbis"
            "flac" -> "flac"
            "wav" -> "pcm_s16le"
            else -> throw IllegalArgumentException("Unsupported audio output: $extension")
        }
        val args = arrayListOf(
            "-y", "-i", input.absolutePath, "-map", "0:a:0", "-vn",
            "-map_metadata", "0", "-c:a", codec
        )
        if (bitrateKbps > 0 && extension !in listOf("flac", "wav")) {
            args.addAll(listOf("-b:a", "${bitrateKbps}k"))
        }
        addFaststart(args, output)
        args.add(output.absolutePath)
        return args
    }

    private fun addFaststart(args: MutableList<String>, output: File) {
        if (output.extension.lowercase() in listOf("mp4", "m4a", "m4v", "mov")) {
            args.addAll(listOf("-movflags", "+faststart"))
        }
    }
}
