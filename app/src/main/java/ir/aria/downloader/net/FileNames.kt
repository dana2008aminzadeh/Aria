package ir.aria.downloader.net

import java.net.URI
import java.net.URLDecoder

/** File name derivation, sanitising and collision handling. Pure JVM, unit tested. */
object FileNames {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val WHITESPACE = Regex("""\s+""")
    private const val MAX_LEN = 140

    fun sanitize(input: String, fallback: String = "download"): String {
        var name = input
            .replace(ILLEGAL, "_")
            .replace(WHITESPACE, " ")
            .trim()
            .trim('.', ' ', '_')
        if (name.isEmpty()) name = fallback
        if (name.length > MAX_LEN) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0 && name.length - dot <= 12) {
                name.substring(0, MAX_LEN - (name.length - dot)) + name.substring(dot)
            } else {
                name.substring(0, MAX_LEN)
            }
        }
        return name.ifEmpty { fallback }
    }

    /** Last path segment of an url, url-decoded, or null when the url does not name a file. */
    fun fromUrl(url: String): String? {
        return try {
            val uri = URI(url)
            val path = uri.rawPath ?: return null
            val rawSegment = path.substringAfterLast('/')
            if (rawSegment.isBlank()) return null
            val decoded = try {
                URLDecoder.decode(rawSegment.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                rawSegment
            }
            val name = sanitize(decoded, fallback = "")
            if (name.isBlank() || !name.contains('.')) null else name
        } catch (e: Exception) {
            null
        }
    }

    /** Parses `Content-Disposition`, supporting RFC 5987 `filename*` and quoted values. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.split(';')
        var starName: String? = null
        var plainName: String? = null
        for (part in parts) {
            val trimmed = part.trim()
            val lower = trimmed.lowercase()
            when {
                lower.startsWith("filename*=") -> {
                    val value = trimmed.substringAfter('=').trim()
                    starName = decodeExtended(value)
                }
                lower.startsWith("filename=") -> {
                    var value = trimmed.substringAfter('=').trim()
                    if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length - 1)
                    }
                    plainName = value.replace("\\\"", "\"")
                }
            }
        }
        val candidate = starName ?: plainName ?: return null
        val base = candidate.substringAfterLast('/').substringAfterLast('\\')
        if (base.isBlank()) return null
        return sanitize(base, fallback = "")
    }

    private fun decodeExtended(value: String): String? {
        // format: charset'lang'percent-encoded  e.g. UTF-8''my%20file.mp4
        return try {
            val withoutPrefix = if (value.count { it == '\'' } >= 2) {
                value.substringAfter('\'').substringAfter('\'')
            } else value
            URLDecoder.decode(withoutPrefix.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** Forces the real output extension, including when a media title ends in .mp4/.m4a. */
    fun withExtension(title: String, extension: String): String {
        val mediaExtensions = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "ts", "3gp",
            "mp3", "m4a", "aac", "opus", "ogg", "wav", "flac", "amr"
        )
        val ext = extension.lowercase().removePrefix(".").ifBlank { "bin" }
        val cleanTitle = sanitize(title, "media")
        val stem = if (extensionOf(cleanTitle) in mediaExtensions) {
            cleanTitle.substringBeforeLast('.')
        } else cleanTitle
        return sanitize("$stem.$ext", "media.$ext")
    }

    /** Adds " (1)", " (2)" ... before the extension until the name is free. */
    fun ensureUnique(name: String, taken: Set<String>): String {
        val safe = sanitize(name)
        if (!taken.contains(safe)) return safe
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var index = 1
        while (index < 10_000) {
            val candidate = "$stem ($index)$ext"
            if (!taken.contains(candidate)) return candidate
            index++
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    /** Best-effort mime type for a file name; used when the server does not send one. */
    fun guessMime(name: String): String {
        val ext = extensionOf(name)
        return MimeTypes.mimeForExtension(ext)
    }
}
