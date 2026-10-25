package ir.aria.downloader.engine

import java.net.URI

/** Decides which engine should handle a link. */
enum class EngineKind {
    /** Direct file download (the fast native engine). */
    HTTP,

    /** Media page: yt-dlp extracts the real stream(s). */
    MEDIA,

    /** Spotify page: resolved to a searchable track and downloaded as audio. */
    SPOTIFY;

    companion object {
        fun fromName(name: String?): EngineKind =
            values().firstOrNull { it.name == name } ?: HTTP
    }
}

/**
 * URL classification. Kept free of Android imports so it can be unit tested.
 */
object UrlClassifier {

    private val FILE_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "ts", "3gp", "m3u8", "mpd",
        "mp3", "m4a", "aac", "opus", "ogg", "wav", "flac", "amr",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "avif",
        "pdf", "zip", "rar", "7z", "tar", "gz", "apk", "xapk", "iso",
        "txt", "srt", "vtt", "json", "xml", "csv", "epub",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    )

    /** Hosts that always need the extractor, even when the path looks like a file. */
    private val MEDIA_HOSTS = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "music.youtube.com",
        "instagram.com", "instagr.am", "tiktok.com", "twitter.com", "x.com",
        "facebook.com", "fb.watch", "vimeo.com", "dailymotion.com", "dai.ly",
        "soundcloud.com", "bandcamp.com", "twitch.tv", "reddit.com", "redd.it",
        "pornhub.com", "pornhub.net", "pornhub.org",
        "pornhubpremium.com", "pornhubpremium.net", "pornhubpremium.org",
        "xvideos.com", "xhamster.com", "redtube.com", "youporn.com",
        "bilibili.com", "rumble.com", "odysee.com", "streamable.com", "imgur.com",
        "pinterest.com", "pin.it", "linkedin.com", "snapchat.com", "vk.com", "ok.ru",
        "archive.org", "mixcloud.com", "audiomack.com", "likee.video", "kwai.com",
        "aparat.com", "namasha.com", "filimo.com", "telewebion.com",
        "ted.com", "bbc.co.uk", "cnn.com", "spotify.com", "open.spotify.com"
    )

    private val SPOTIFY_HOSTS = setOf("open.spotify.com", "spotify.com", "play.spotify.com")

    private val PAGE_EXTENSIONS = setOf("html", "htm", "php", "asp", "aspx", "jsp")

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }

    fun pathOf(url: String): String = try {
        URI(url).path.orEmpty()
    } catch (e: Exception) {
        ""
    }

    fun extensionOf(url: String): String {
        val path = pathOf(url).substringBeforeLast('?')
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun isSpotify(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return SPOTIFY_HOSTS.contains(host)
    }

    fun isKnownMediaHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** A link that points straight at a downloadable file. */
    fun isDirectFile(url: String): Boolean {
        if (isSpotify(url)) return false
        if (isKnownMediaHost(url)) {
            // even on media hosts, a path with a real file extension is usually a CDN link
            val ext = extensionOf(url)
            return ext.isNotEmpty() && ext in setOf("mp4", "m4a", "mp3", "jpg", "jpeg", "png", "webp", "pdf", "zip", "apk")
        }
        val ext = extensionOf(url)
        if (ext.isNotEmpty() && FILE_EXTENSIONS.contains(ext)) return true
        if (ext.isNotEmpty() && PAGE_EXTENSIONS.contains(ext)) return false
        // no extension: files usually come from a path with a filename, pages do not
        val last = pathOf(url).trimEnd('/').substringAfterLast('/')
        return ext.isEmpty() && last.contains('.') && last.substringAfterLast('.').length in 2..5
    }

    fun classify(url: String): EngineKind = when {
        isSpotify(url) -> EngineKind.SPOTIFY
        isKnownMediaHost(url) -> EngineKind.MEDIA
        isDirectFile(url) -> EngineKind.HTTP
        else -> {
            // Unknown host without a file extension: try the extractor first, because it can
            // also download plain files while the http engine cannot parse web pages.
            if (extensionOf(url).isEmpty() && pathOf(url).trimEnd('/').isNotEmpty() &&
                !pathOf(url).trimEnd('/').substringAfterLast('/').contains('.')
            ) EngineKind.MEDIA else EngineKind.HTTP
        }
    }

    /** A spotify link type (track/album/playlist/artist/episode/show). */
    fun spotifyType(url: String): String {
        val parts = pathOf(url).split('/').filter { it.isNotBlank() }
        val index = parts.indexOfFirst { it in setOf("track", "album", "playlist", "artist", "episode", "show", "user") }
        return if (index >= 0) parts[index] else "track"
    }

    fun spotifyId(url: String): String? {
        val parts = pathOf(url).split('/').filter { it.isNotBlank() }
        val index = parts.indexOfFirst { it in setOf("track", "album", "playlist", "artist", "episode", "show", "user") }
        if (index < 0 || parts.size <= index + 1) return null
        return parts[index + 1].substringBefore('?')
    }
}
