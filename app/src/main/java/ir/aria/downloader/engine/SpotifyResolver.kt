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
    private val readTimeoutMs: Int = 20_000
) {

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
    }

    /** Parses the embedded JSON of a spotify embed page. Pure function, unit tested. */
    fun parseEmbed(html: String): Collection {
        val match = NEXT_DATA.find(html)
            ?: return Collection(false, "", emptyList(), "صفحهٔ اسپاتیفای قابل خواندن نبود")
        return try {
            val root = JSONObject(match.groupValues[1])
            val entity = root
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
                ?: return Collection(false, "", emptyList(), "ساختار صفحهٔ اسپاتیفای تغییر کرده است")

            val name = entity.optString("title").ifBlank {
                entity.optString("name").ifBlank { "اسپاتیفای" }
            }

            val tracks = ArrayList<Track>()
            val list = entity.optJSONArray("trackList")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val title = item.optString("title")
                    if (title.isBlank()) continue
                    val subtitle = item.optString("subtitle").ifBlank {
                        item.optJSONArray("artists")?.let { array ->
                            (0 until array.length()).mapNotNull { idx ->
                                array.optJSONObject(idx)?.optString("name")?.takeIf { it.isNotBlank() }
                            }.joinToString(", ")
                        }.orEmpty()
                    }
                    tracks.add(Track(
                        title = title,
                        artists = subtitle,
                        query = buildQuery(title, subtitle),
                        durationSec = durationSeconds(item.optLong("duration", -1L))
                    ))
                }
            }
            if (tracks.isEmpty() && entity.optString("type") == "track" && name.isNotBlank()) {
                val subtitle = entity.optString("subtitle").ifBlank {
                    entity.optJSONArray("artists")?.let { array ->
                        (0 until array.length()).mapNotNull { idx ->
                            array.optJSONObject(idx)?.optString("name")?.takeIf { it.isNotBlank() }
                        }.joinToString(", ")
                    }.orEmpty()
                }
                tracks.add(Track(
                    title = name,
                    artists = subtitle,
                    query = buildQuery(name, subtitle),
                    durationSec = durationSeconds(entity.optLong("duration", -1L))
                ))
            }
            if (tracks.isEmpty()) {
                Collection(false, name, emptyList(), "ترکی در این لینک پیدا نشد (شاید پادکست یا آرتیست باشد)")
            } else {
                Collection(true, name, tracks)
            }
        } catch (e: Exception) {
            Collection(false, "", emptyList(), "خواندن اطلاعات اسپاتیفای ناموفق بود")
        }
    }

    /** Fetches and parses a spotify url (track / album / playlist). Blocking: call on IO. */
    fun resolve(url: String): Collection = try {
        resolveUnsafe(url)
    } catch (t: Throwable) {
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
        val html = try {
            httpGet(embedUrl)
        } catch (e: Exception) {
            return Collection(false, "", emptyList(), "اتصال به اسپاتیفای ناموفق بود")
        }
        return parseEmbed(html)
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
            null
        }
    }

    private fun httpGet(url: String): String {
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
            return body
        }
        throw IllegalStateException("too many redirects")
    }
}
