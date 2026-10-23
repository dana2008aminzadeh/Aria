package ir.aria.downloader.net

import java.net.IDN
import java.net.URI

/** Validates/normalizes the optional proxy before it is handed to yt-dlp. */
object ProxyUrlValidator {
    private val schemes = setOf("http", "https", "socks5", "socks5h")

    @Throws(IllegalArgumentException::class)
    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val withScheme = if ("://" in value) value else "http://$value"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid proxy URL", e)
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        require(scheme in schemes) { "Supported proxy schemes: http, https, socks5, socks5h" }
        require(!uri.host.isNullOrBlank()) { "Proxy host is missing" }
        require(uri.port in -1..65535 && uri.port != 0) { "Invalid proxy port" }
        require(uri.query == null && uri.fragment == null) { "Proxy URL cannot have query/fragment" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Proxy URL cannot have a path" }

        // Rebuild the authority so Unicode host names and a missing scheme are normalized.
        val userInfo = uri.rawUserInfo?.let { "$it@" }.orEmpty()
        val host = IDN.toASCII(uri.host)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$userInfo$host$port"
    }
}
