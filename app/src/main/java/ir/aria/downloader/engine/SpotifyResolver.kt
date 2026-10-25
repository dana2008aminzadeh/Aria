package ir.aria.downloader.engine

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify has DRM, so its audio can never be downloaded directly. What we *can* do -
 * and what every "Spotify downloader" actually does - is read the public metadata of a
 * track/album/playlist and then fetch the matching audio from a real stream source.
 *
 * Metadata source: the public embed page (`/embed/{type}/{id}`), which contains a
 * `__NEXT_DATA__` JSON blob with the track list. No API key or login required.
 */
class SpotifyResolver(
    private val userAgent: String = DEFAULT_UA,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val fetcher: ((String) -> String)? = null,
    /** Optional diagnostics sink (LinkIngestor forwards to DiagLog in the app). */
    private val log: ((String) -> Unit)? = null
) {

    private fun diag(message: String) {
        log?.invoke(message)
    }

    data class Track(
        val title: String,
        val artists: String,
        val query: String,
        val durationSec: Long? = null
    )

    data class Collection(
        val ok: Boolean,
        val name: String,
        val tracks: List<Track>,
        val error: String? = null
    )

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        private val NEXT_DATA = Regex(
            """<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        fun cleanTitle(title: String): String = title
            .replace(Regex("""\s*[\(\[]\s*(?:feat\.|ft\.|with)\s+[^\)\]]*[\)\]]""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+(?:feat\.|ft\.)\s+.*$""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        fun primaryArtist(artists: String): String = artists.split(',', '&')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        /** Builds the search query used against the media engine. */
        fun buildQuery(title: String, artists: String): String =
            listOf(primaryArtist(artists), cleanTitle(title))
                .filter { it.isNotBlank() }.joinToString(" ") + " audio"

        /** A second pass with all artists; used only if the first candidates are unsafe. */
        fun preciseQuery(title: String, artists: String): String =
            listOf(artists.trim(), cleanTitle(title))
                .filter { it.isNotBlank() }.joinToString(" ") + " official audio"

        fun durationSeconds(durationMs: Long): Long? =
            if (durationMs > 0) (durationMs + 500L) / 1000L else null

        private fun findEntity(value: Any?, depth: Int = 0): JSONObject? {
            if (value == null || depth > 16) return null
            when (value) {
                is JSONObject -> {
                    val type = value.optString("type")
                    val hasName = value.optString("title").isNotBlank() ||
                        value.optString("name").isNotBlank()
                    val isTrack = type == "track" && hasName
                    val isCollection = type in setOf("album", "playlist") && hasName &&
                        value.optJSONArray("trackList") != null
                    if (isTrack || isCollection) return value
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        findEntity(value.opt(keys.next()), depth + 1)?.let { return it }
                    }
                }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        findEntity(value.opt(index), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun artistsOf(item: JSONObject): String {
            val subtitle = item.optString("subtitle")
            if (subtitle.isNotBlank()) return subtitle
            val array = item.optJSONArray("artists") ?: return ""
            return (0 until array.length()).mapNotNull { index ->
                when (val artist = array.opt(index)) {
                    is JSONObject -> artist.optString("name").takeIf { it.isNotBlank() }
                    is String -> artist.takeIf { it.isNotBlank() }
                    else -> null
                }
            }.joinToString(", ")
        }

        private fun durationOf(item: JSONObject): Long? {
            for (key in listOf("duration", "durationMs", "duration_ms")) {
                if (!item.has(key)) continue
                durationSeconds(item.optLong(key, -1L))?.let { return it }
            }
            return null
        }
    }

    /** Parses the embedded JSON of a spotify embed page. Pure function, unit tested. */
    fun parseEmbed(html: String): Collection {
        val match = NEXT_DATA.find(html)
        if (match == null) {
            diag("parseEmbed: __NEXT_DATA__ not found (html " + html.length + " chars)")
            return Collection(false, "", emptyList(), "صفحهٔ اسپاتیفای قابل خواندن نبود")
        }
        return try {
            val root = JSONObject(match.groupValues[1])
            val directEntity = root
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
            val entity = directEntity?.takeIf {
                (it.optString("type") == "track" &&
                    (it.optString("title").isNotBlank() || it.optString("name").isNotBlank())) ||
                    it.optJSONArray("trackList") != null
            } ?: findEntity(root)
                ?: return Collection(false, "", emptyList(), "ساختار صفحهٔ اسپاتیفای تغییر کرده است")

            val name = entity.optString("title").ifBlank {
                entity.optString("name").ifBlank { "اسپاتیفای" }
            }

            val tracks = ArrayList<Track>()
            val list = entity.optJSONArray("trackList")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val title = item.optString("title").ifBlank { item.optString("name") }
                    if (title.isBlank()) continue
                    val artists = artistsOf(item)
                    tracks.add(Track(
                        title = title,
                        artists = artists,
                        query = buildQuery(title, artists),
                        durationSec = durationOf(item)
                    ))
                }
            }
            if (tracks.isEmpty() && entity.optString("type") == "track" && name.isNotBlank()) {
                val artists = artistsOf(entity)
                tracks.add(Track(
                    title = name,
                    artists = artists,
                    query = buildQuery(name, artists),
                    durationSec = durationOf(entity)
                ))
            }
            diag(
                "parseEmbed: name='" + name + "' tracks=" + tracks.size + tracks.take(5)
                    .joinToString(" | ") { "'" + it.title + "' by '" + it.artists + "' dur=" + it.durationSec }
            )
            if (tracks.isEmpty()) {
                Collection(false, name, emptyList(), "ترکی در این لینک پیدا نشد (شاید پادکست یا آرتیست باشد)")
            } else {
                Collection(true, name, tracks)
            }
        } catch (e: Exception) {
            diag("parseEmbed exception: " + e.javaClass.simpleName + " " + e.message)
            Collection(false, "", emptyList(), "خواندن اطلاعات اسپاتیفای ناموفق بود")
        }
    }

    /** Fetches and parses a spotify url (track / album / playlist). Blocking: call on IO. */
    fun resolve(url: String): Collection = try {
        diag("resolve start url=" + url)
        val result = resolveUnsafe(url)
        diag("resolve done ok=" + result.ok + " name='" + result.name + "' tracks=" + result.tracks.size +
            " error='" + result.error + "'")
        result
    } catch (t: Throwable) {
        diag("resolve exception: " + t.javaClass.simpleName + " " + t.message)
        Collection(false, "", emptyList(), "خواندن اطلاعات اسپاتیفای ناموفق بود")
    }

    private fun resolveUnsafe(url: String): Collection {
        val type = UrlClassifier.spotifyType(url)
        val id = UrlClassifier.spotifyId(url)
            ?: return Collection(false, "", emptyList(), "لینک اسپاتیفای معتبر نیست")
        if (type == "artist" || type == "show" || type == "episode") {
            return Collection(
                false, "", emptyList(),
                "لینک‌های آرتیست/پادکست اسپاتیفای پشتیبانی نمی‌شوند؛ لینک آهنگ، آلبوم یا پلی‌لیست بدهید."
            )
        }
        val embedUrl = "https://open.spotify.com/embed/$type/$id"
        diag("type=$type id=$id embed=$embedUrl")
        val parsed = try {
            parseEmbed(httpGet(embedUrl))
        } catch (e: Exception) {
            diag("embed fetch failed: " + e.javaClass.simpleName + " " + e.message)
            null
        }
        if (parsed?.ok == true) return parsed
        diag("embed path not usable (parsed ok=" + parsed?.ok + ", error='" + parsed?.error + "')")

        // oEmbed cannot provide album/playlist entries or duration, but it is a useful
        // independent public metadata path for one track when the embed layout/API changes.
        if (type == "track") {
            diag("trying oEmbed fallback for single track")
            val fallback = resolveViaOEmbed(url)
            if (fallback != null) {
                diag("oEmbed fallback ok: '" + fallback.title + "' by '" + fallback.artists + "'")
                return Collection(true, fallback.title, listOf(fallback))
            }
            diag("oEmbed fallback returned nothing")
        }
        return parsed ?: Collection(false, "", emptyList(), "اتصال به اسپاتیفای ناموفق بود")
    }

    /** oEmbed fallback (title + author) used when the embed page is unavailable. */
    fun resolveViaOEmbed(url: String): Track? {
        return try {
            val api = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(url, "UTF-8")
            val json = JSONObject(httpGet(api))
            val title = json.optString("title").ifBlank { return null }
            val author = json.optString("author_name")
            Track(title, author, buildQuery(title, author))
        } catch (e: Exception) {
            diag("oEmbed exception: " + e.javaClass.simpleName + " " + e.message)
            null
        }
    }

    private fun httpGet(url: String): String {
        fetcher?.let { return it(url) }
        var current = url
        var hops = 0
        while (hops++ < 5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            }
            val code = connection.responseCode
            diag("GET " + current.take(200) + " -> HTTP " + code)
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: ""
                connection.disconnect()
                if (location.isBlank()) throw IllegalStateException("redirect")
                current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use {
                it.readText()
            }
            connection.disconnect()
            diag("GET body " + body.length + " chars")
            return body
        }
        throw IllegalStateException("too many redirects")
    }
}
