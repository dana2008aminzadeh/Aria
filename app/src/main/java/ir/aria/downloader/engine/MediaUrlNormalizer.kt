package ir.aria.downloader.engine

import java.net.URI
import java.net.URLDecoder

/** Canonicalizes media share/embed links before they are handed to yt-dlp. */
object MediaUrlNormalizer {

    private val APARAT_ID = Regex("^[A-Za-z0-9]+$")
    private val PORNHUB_ID = Regex("^[A-Za-z0-9]+$")
    private val INSTAGRAM_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
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
            host == "instagram.com" || host.endsWith(".instagram.com") || host == "instagr.am" ->
                normalizeInstagram(uri, original)
            host == "redd.it" -> normalizeRedditShort(uri, original)
            host == "reddit.com" || host.endsWith(".reddit.com") -> normalizeReddit(uri, original)
            host.contains("pinterest.") -> normalizePinterest(uri, original)
            host == "player.vimeo.com" -> normalizeVimeoPlayer(uri, original)
            host == "m.twitch.tv" -> original.replace("://m.twitch.tv", "://www.twitch.tv")
            pornhubBase(host) != null -> normalizePornhub(uri, host, original)
            else -> original
        }
    }

    private fun normalizeInstagram(uri: URI, original: String): String {
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        if (parts.firstOrNull()?.equals("share", true) == true) {
            // A /share/... token is not a shortcode; the extractor must follow its redirect.
            return original
        }
        for (index in 0 until parts.lastIndex) {
            val marker = parts[index].lowercase()
            if (marker !in setOf("p", "reel", "reels", "tv")) continue
            val id = parts[index + 1]
            if (marker == "reels" && id.equals("audio", true)) return original
            if (!INSTAGRAM_ID.matches(id)) return original
            val kind = if (marker == "reels") "reel" else marker
            return "https://www.instagram.com/$kind/$id/"
        }
        return original
    }

    /** redd.it/<postId> only matches yt-dlp's generic extractor; canonical comments URL hits RedditIE. */
    private fun normalizeRedditShort(uri: URI, original: String): String {
        val id = uri.rawPath.orEmpty().split('/').firstOrNull { it.isNotBlank() }
            ?.takeIf { PORNHUB_ID.matches(it) } ?: return original
        return "https://www.reddit.com/comments/$id/"
    }

    private fun normalizeReddit(uri: URI, original: String): String {
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val idx = parts.indexOf("comments")
        val id = parts.getOrNull(idx + 1)?.takeIf { idx >= 0 && PORNHUB_ID.matches(it) }
            ?: return original
        return "https://www.reddit.com/comments/$id/"
    }

    /** Any Pinterest TLD (pinterest.fr/.co.uk/...) with a /pin/<digits> path -> canonical .com pin. */
    private fun normalizePinterest(uri: URI, original: String): String {
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val idx = parts.indexOf("pin")
        val id = parts.getOrNull(idx + 1)?.takeIf { idx >= 0 && it.all { c -> c.isDigit() } }
            ?: return original
        return "https://www.pinterest.com/pin/$id/"
    }

    private fun normalizeVimeoPlayer(uri: URI, original: String): String {
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        val id = parts.getOrNull(1)?.takeIf {
            parts.firstOrNull() == "video" && it.all { c -> c.isDigit() }
        } ?: return original
        return "https://vimeo.com/$id"
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
