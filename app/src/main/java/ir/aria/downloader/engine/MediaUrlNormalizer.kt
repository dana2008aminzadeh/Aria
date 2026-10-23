package ir.aria.downloader.engine

import java.net.URI
import java.net.URLDecoder

/** Canonicalizes media share/embed links before they are handed to yt-dlp. */
object MediaUrlNormalizer {

    private val APARAT_ID = Regex("^[A-Za-z0-9]+$")
    private val PORNHUB_ID = Regex("^[A-Za-z0-9]+$")
    private val PORNHUB_HOSTS = listOf(
        "pornhub.com", "pornhub.net", "pornhub.org",
        "pornhubpremium.com", "pornhubpremium.net", "pornhubpremium.org"
    )

    fun normalize(url: String): String {
        val original = url.trim()
        val uri = try {
            URI(original)
        } catch (e: Exception) {
            return original
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return original
        return when {
            host == "aparat.com" || host.endsWith(".aparat.com") -> normalizeAparat(uri, original)
            pornhubBase(host) != null -> normalizePornhub(uri, host, original)
            else -> original
        }
    }

    private fun normalizeAparat(uri: URI, original: String): String {
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val marker = when {
            parts.contains("videohash") -> "videohash"
            parts.contains("v") -> "v"
            else -> return original
        }
        val id = parts.getOrNull(parts.indexOf(marker) + 1)
            ?.takeIf { APARAT_ID.matches(it) }
            ?: return original
        // This also converts the long /video/video/embed/.../videohash/<id>/ share URL
        // to the canonical form understood directly by AparatIE.
        return "https://www.aparat.com/v/$id"
    }

    private fun normalizePornhub(uri: URI, host: String, original: String): String {
        val base = pornhubBase(host) ?: return original
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val fromEmbed = if (parts.firstOrNull()?.equals("embed", true) == true) {
            parts.getOrNull(1)
        } else {
            null
        }
        val id = (fromEmbed ?: queryParameter(uri.rawQuery, "viewkey"))
            ?.takeIf { PORNHUB_ID.matches(it) }
            ?: return original
        // Canonical URLs work for normal, localized, embed and premium share links and
        // discard tracking parameters without losing the required viewkey.
        return "https://www.$base/view_video.php?viewkey=$id"
    }

    private fun pornhubBase(host: String): String? =
        PORNHUB_HOSTS.firstOrNull { host == it || host.endsWith(".$it") }

    private fun queryParameter(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        for (part in rawQuery.split('&')) {
            val key = part.substringBefore('=')
            if (!key.equals(name, true)) continue
            val value = part.substringAfter('=', "")
            return try {
                URLDecoder.decode(value, "UTF-8")
            } catch (e: Exception) {
                value
            }
        }
        return null
    }
}
