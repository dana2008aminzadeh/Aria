package ir.aria.downloader.net

/** Small extension <-> mime map used to label and open downloaded files. */
object MimeTypes {

    private val EXT_TO_MIME = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo", "mov" to "video/quicktime",
        "flv" to "video/x-flv", "ts" to "video/mp2t", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "opus" to "audio/opus", "ogg" to "audio/ogg", "wav" to "audio/wav",
        "flac" to "audio/flac", "amr" to "audio/amr",
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "svg" to "image/svg+xml", "heic" to "image/heic", "avif" to "image/avif",
        "pdf" to "application/pdf", "zip" to "application/zip", "rar" to "application/x-rar-compressed",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar",
        "gz" to "application/gzip", "apk" to "application/vnd.android.package-archive",
        "xapk" to "application/octet-stream", "txt" to "text/plain", "srt" to "application/x-subrip",
        "vtt" to "text/vtt", "json" to "application/json", "xml" to "application/xml",
        "html" to "text/html", "epub" to "application/epub+zip",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "iso" to "application/octet-stream", "torrent" to "application/x-bittorrent"
    )

    private val MIME_TO_EXT = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv",
        "video/quicktime" to "mov", "video/x-msvideo" to "avi", "video/mp2t" to "ts",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg",
        "audio/opus" to "opus", "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/flac" to "flac",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "image/svg+xml" to "svg", "image/bmp" to "bmp", "image/heic" to "heic", "image/avif" to "avif",
        "application/pdf" to "pdf", "application/zip" to "zip",
        "application/x-rar-compressed" to "rar", "application/x-7z-compressed" to "7z",
        "application/vnd.android.package-archive" to "apk", "text/plain" to "txt",
        "application/json" to "json", "application/octet-stream" to "bin",
        "application/x-subrip" to "srt", "text/vtt" to "vtt", "application/epub+zip" to "epub"
    )

    fun mimeForExtension(ext: String): String =
        EXT_TO_MIME[ext.lowercase()] ?: "application/octet-stream"

    fun extensionForMime(mime: String): String {
        val clean = mime.substringBefore(';').trim().lowercase()
        return MIME_TO_EXT[clean] ?: "bin"
    }

    fun normalize(contentType: String?, fileName: String): String {
        val clean = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (clean.isNotEmpty() && clean != "application/octet-stream" &&
            clean != "binary/octet-stream" && clean != "application/force-download" &&
            clean != "application/download" && clean != "text/html"
        ) {
            return clean
        }
        val guessed = guessFromName(fileName)
        return if (clean == "text/html") {
            // Some servers report text/html for media files (hotlink pages, login walls...).
            val ext = FileNames.extensionOf(fileName)
            when {
                guessed != "application/octet-stream" -> guessed
                ext == "html" || ext == "htm" -> clean
                else -> "application/octet-stream"
            }
        } else guessed
    }

    fun guessFromName(fileName: String): String = mimeForExtension(FileNames.extensionOf(fileName))

    fun categoryOf(mime: String, fileName: String): Category {
        val type = mime.substringBefore('/').lowercase()
        return when {
            type == "video" -> Category.VIDEO
            type == "audio" -> Category.AUDIO
            type == "image" -> Category.IMAGE
            mime == "application/pdf" -> Category.PDF
            mime.contains("zip") || mime.contains("rar") || mime.contains("7z") ||
                mime.contains("tar") || mime.contains("gzip") -> Category.ARCHIVE
            mime.contains("android.package") -> Category.APP
            type == "text" || mime.contains("json") || mime.contains("xml") -> Category.TEXT
            else -> Category.OTHER
        }
    }

    enum class Category { VIDEO, AUDIO, IMAGE, PDF, ARCHIVE, APP, TEXT, OTHER }
}
