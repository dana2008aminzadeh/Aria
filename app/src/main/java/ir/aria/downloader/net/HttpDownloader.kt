package ir.aria.downloader.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A small, dependency free, resumable HTTP downloader.
 *
 * Features: manual redirect following, `Range`/`If-Range` resume, chunked/unknown length
 * support, progress + speed + ETA reporting and cooperative cancellation.
 */
class HttpDownloader(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 30_000
) {

    data class Metadata(
        val finalUrl: String,
        val totalBytes: Long,
        val resumable: Boolean,
        val etag: String?,
        val lastModified: String?,
        val contentDisposition: String?,
        val contentType: String?
    )

    interface Listener {
        fun onMetadata(metadata: Metadata) {}
        fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBps: Long, etaSeconds: Long) {}
    }

    class HttpException(val code: Int, message: String) : IOException(message)
    class CancelledException : IOException("download cancelled")

    @Volatile
    private var aborted = false

    @Volatile
    private var connection: HttpURLConnection? = null

    /** Aborts the transfer from another thread. The blocked read is released by disconnecting. */
    fun abort() {
        aborted = true
        runCatching { connection?.disconnect() }
    }

    /**
     * Downloads [url] into [target], resuming when the file already has bytes.
     * [validator] should be an ETag or Last-Modified value from a previous attempt.
     */
    fun download(url: String, target: File, validator: String?, listener: Listener): Metadata {
        aborted = false
        target.parentFile?.mkdirs()
        var resumeFrom = if (target.exists()) target.length() else 0L
        var attempt = 0

        while (attempt++ < 3) {
            checkAborted()
            val conn = open(url, resumeFrom, validator)
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                if (aborted) throw CancelledException()
                throw e
            }

            if (code == 416 && resumeFrom > 0) {
                // The partial file does not match the remote file any more: start over.
                conn.disconnect()
                target.delete()
                resumeFrom = 0
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw HttpException(code, "HTTP $code")
            }

            val partial = code == 206
            var startAt: Long
            var total: Long
            if (partial) {
                val range = parseContentRange(conn.getHeaderField("Content-Range"))
                if (range == null || range.first != resumeFrom) {
                    conn.disconnect()
                    target.delete()
                    resumeFrom = 0
                    continue
                }
                startAt = range.first
                total = range.second
            } else {
                // Server ignored our Range header (or this is a fresh download): restart at 0.
                startAt = 0
                total = conn.contentLengthLong
            }

            val meta = Metadata(
                finalUrl = conn.url.toString(),
                totalBytes = total,
                resumable = partial || conn.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true,
                etag = conn.getHeaderField("ETag"),
                lastModified = conn.getHeaderField("Last-Modified"),
                contentDisposition = conn.getHeaderField("Content-Disposition"),
                contentType = conn.getHeaderField("Content-Type")
            )
            try {
                listener.onMetadata(meta)
            } catch (e: Exception) {
                runCatching { conn.inputStream?.close() }
                conn.disconnect()
                connection = null
                throw e
            }

            try {
                stream(conn, target, startAt, total, listener)
            } catch (e: IOException) {
                if (aborted) throw CancelledException()
                throw e
            } finally {
                runCatching { conn.inputStream?.close() }
                conn.disconnect()
                connection = null
            }
            return meta
        }
        throw IOException("could not download $url")
    }

    private fun stream(
        conn: HttpURLConnection,
        target: File,
        startAt: Long,
        total: Long,
        listener: Listener
    ) {
        val out = FileOutputStream(target, startAt > 0)
        var downloaded = startAt
        try {
            val input = conn.inputStream
            val buffer = ByteArray(256 * 1024)
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            var lastEmit = windowStart

            while (true) {
                if (aborted) throw CancelledException()
                val read = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    if (aborted) throw CancelledException() else throw e
                }
                if (read == -1) break
                out.write(buffer, 0, read)
                downloaded += read
                windowBytes += read

                val now = System.currentTimeMillis()
                if (now - lastEmit >= 400) {
                    val elapsed = (now - windowStart).coerceAtLeast(1)
                    val speed = windowBytes * 1000 / elapsed
                    val eta = if (total > 0 && speed > 0) (total - downloaded) / speed else -1
                    listener.onProgress(downloaded, total, speed, eta)
                    windowStart = now
                    windowBytes = 0
                    lastEmit = now
                }
            }
            if (total > 0 && downloaded < total) {
                throw IOException("connection closed early ($downloaded of $total bytes)")
            }
            listener.onProgress(downloaded, total, 0, -1)
        } finally {
            runCatching { out.flush() }
            runCatching { out.fd.sync() }
            runCatching { out.close() }
        }
    }

    private fun open(url: String, resumeFrom: Long, validator: String?): HttpURLConnection {
        var current = normalizeUrl(url)
        var hops = 0
        while (true) {
            checkAborted()
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (resumeFrom > 0) {
                    setRequestProperty("Range", "bytes=$resumeFrom-")
                    if (!validator.isNullOrBlank()) setRequestProperty("If-Range", validator)
                }
            }
            connection = conn
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                if (aborted) throw CancelledException()
                throw e
            }
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without location header")
                current = resolve(current, location)
                if (++hops > 10) throw IOException("too many redirects")
                continue
            }
            return conn
        }
    }

    private fun checkAborted() {
        if (aborted) throw CancelledException()
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36 AriaDownloader/1.0"

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Parses "bytes 100-199/1000" -> Pair(100, 1000). Total is -1 when unknown. */
        fun parseContentRange(header: String?): Pair<Long, Long>? {
            if (header.isNullOrBlank()) return null
            val regex = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
            val match = regex.find(header) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val total = match.groupValues[3].let { if (it == "*") -1L else it.toLongOrNull() ?: -1L }
            return start to total
        }

        fun normalizeUrl(url: String): String {
            val trimmed = url.trim()
            return try {
                URI(trimmed).toASCIIString()
            } catch (e: Exception) {
                try {
                    URI(trimmed.replace(" ", "%20")).toASCIIString()
                } catch (e2: Exception) {
                    trimmed
                }
            }
        }

        fun resolve(base: String, location: String): String {
            return try {
                URI(base).resolve(location).toASCIIString()
            } catch (e: Exception) {
                try {
                    URL(URL(base), location).toString()
                } catch (e2: Exception) {
                    location
                }
            }
        }
    }
}
