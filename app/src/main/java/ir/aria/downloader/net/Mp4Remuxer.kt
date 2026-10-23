package ir.aria.downloader.net

import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Track
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.io.FileOutputStream

/**
 * Lossless MP4 remuxer.
 *
 * DASH sites (YouTube & friends) serve the video and the audio as two separate files.
 * Instead of shipping a 40 MB ffmpeg binary we re-mux the two ISO-BMFF files with
 * mp4parser: samples are copied untouched (`vide` + `soun` tracks), so it is fast,
 * lossless and works for the codecs the sites deliver in mp4 (H.264 + AAC).
 *
 * Pure JVM (no Android framework classes) which is why it is covered by unit tests.
 */
object Mp4Remuxer {

    class RemuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Result(val file: File, val durationMs: Long, val videoTrack: String, val audioTrack: String)

    /** True when the file looks like an ISO-BMFF/MP4 container (ftyp box at the start). */
    fun looksLikeMp4(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                var read = 0
                while (read < 12) {
                    val n = input.read(header, read, 12 - read)
                    if (n <= 0) break
                    read += n
                }
                val type = String(header, 4, 4, Charsets.US_ASCII)
                type == "ftyp" || type == "moov" || type == "styp"
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Combines [videoFile] and [audioFile] into a single MP4 at [target].
     * Throws [RemuxException] when the containers or codecs are not compatible.
     */
    fun remux(videoFile: File, audioFile: File, target: File): Result {
        if (!videoFile.exists()) throw RemuxException("فایل تصویر پیدا نشد")
        if (!looksLikeMp4(videoFile)) throw RemuxException("فایل تصویر MP4 نیست")
        val hasAudio = audioFile.exists() && audioFile.length() > 0
        if (hasAudio && !looksLikeMp4(audioFile)) throw RemuxException("فایل صدا MP4 نیست")

        val tracks = ArrayList<Track>()
        var videoHandler = ""
        var audioHandler = ""
        try {
            val videoMovie: Movie = MovieCreator.build(videoFile.absolutePath)
            for (track in videoMovie.tracks) {
                val handler = track.handler
                if ("vide".equals(handler, true)) {
                    tracks.add(track)
                    videoHandler = handler
                } else if ("soun".equals(handler, true)) {
                    // video file already contains audio: keep it, do not add a second one
                    tracks.add(track)
                    audioHandler = handler
                } else if (handler.isNullOrBlank() || handler == "meta" || handler == "hint") {
                    continue
                } else {
                    tracks.add(track)
                }
            }
            if (tracks.none { "vide".equals(it.handler, true) }) {
                throw RemuxException("ترک تصویر پیدا نشد")
            }
            if (hasAudio && tracks.none { "soun".equals(it.handler, true) }) {
                val audioMovie = MovieCreator.build(audioFile.absolutePath)
                val audioTrack = audioMovie.tracks.firstOrNull {
                    "soun".equals(it.handler, true)
                } ?: throw RemuxException("ترک صدا پیدا نشد")
                tracks.add(audioTrack)
                audioHandler = "soun"
            }
        } catch (e: RemuxException) {
            throw e
        } catch (e: Throwable) {
            throw RemuxException("ادغام تصویر و صدا ناموفق بود: ${e.javaClass.simpleName}", e)
        }

        // Each source file numbers its tracks from 1. Duplicate track ids make the builder
        // silently overwrite one track with the other (the merged file ended up with the video
        // track twice and no audio), so every track gets a unique id.
        var nextTrackId = 1L
        for (track in tracks) {
            try {
                track.trackMetaData.trackId = nextTrackId++
            } catch (e: Exception) {
                // metadata is read-only for exotic sources: leave the id untouched
            }
        }

        val movie = Movie()
        movie.setTracks(tracks)

        try {
            val container = DefaultMp4Builder().build(movie)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                container.writeContainer(out.channel)
                out.fd.sync()
            }
        } catch (e: Throwable) {
            target.delete()
            throw RemuxException("نوشتن فایل نهایی ناموفق بود: ${e.javaClass.simpleName}", e)
        }

        if (!target.exists() || target.length() < 1024) {
            target.delete()
            throw RemuxException("فایل خروجی خالی است")
        }

        // Duration is expressed in each track's own timescale: convert to milliseconds.
        val durationMs = runCatching {
            MovieCreator.build(target.absolutePath).tracks.maxOfOrNull { t ->
                val timescale = t.trackMetaData.timescale.takeIf { it > 0 } ?: 1000L
                t.duration * 1000L / timescale
            }
        }.getOrNull() ?: 0L
        return Result(target, durationMs, videoHandler, audioHandler)
    }
}
