package ir.aria.downloader.engine

import ir.aria.downloader.data.NewDownload
import ir.aria.downloader.net.FileNames
import java.util.Locale

/**
 * Turns a list of arbitrary links into queue requests:
 *
 *  - direct file links          -> native engine
 *  - media pages / playlists    -> yt-dlp engine (playlists are expanded to single items)
 *  - spotify link               -> public metadata -> search -> audio download
 *
 * Everything that cannot be resolved is reported as a warning instead of silently failing.
 */
class LinkIngestor(
    private val engine: ExtractorApi,
    private val spotifyLookup: (String) -> SpotifyResolver.Collection = SpotifyResolver()::resolve
) {

    companion object {
        /** Hard cap so a huge playlist cannot freeze the phone. */
        const val MAX_ITEMS = 120
    }

    data class Report(
        val requests: List<NewDownload>,
        val warnings: List<String>,
        val skipped: Int = 0
    ) {
        val isEmpty: Boolean get() = requests.isEmpty()
    }

    suspend fun ingest(
        links: List<String>,
        preset: String,
        onStatus: (String) -> Unit = {}
    ): Report {
        val requests = ArrayList<NewDownload>()
        val warnings = ArrayList<String>()
        var skipped = 0

        for (rawLink in links) {
            val link = MediaUrlNormalizer.normalize(rawLink)
            if (requests.size >= MAX_ITEMS) {
                skipped = links.size - requests.size
                warnings.add("به سقف $MAX_ITEMS مورد در هر بار رسیدید؛ بقیه لینک‌ها اضافه نشدند.")
                break
            }
            try {
                when (UrlClassifier.classify(link)) {
                EngineKind.HTTP -> {
                    val name = FileNames.fromUrl(link)
                    requests.add(
                        NewDownload(
                            url = link,
                            fileName = name,
                            engine = EngineKind.HTTP,
                            preset = preset,
                            mimeType = if (name != null) FileNames.guessMime(name) else "application/octet-stream"
                        )
                    )
                }

                EngineKind.SPOTIFY -> {
                    onStatus("اسپاتیفای: خواندن اطلاعات…")
                    val collection = try {
                        spotifyLookup(link)
                    } catch (t: Throwable) {
                        SpotifyResolver.Collection(false, "", emptyList(), "اسپاتیفای قابل خواندن نبود")
                    }
                    if (!collection.ok) {
                        warnings.add(collection.error ?: "اسپاتیفای قابل خواندن نبود")
                        continue
                    }
                    var added = 0
                    for (track in collection.tracks) {
                        if (requests.size >= MAX_ITEMS) break
                        onStatus("اسپاتیفای: ${track.title}")
                        val hit = engine.search(track.query, 1).firstOrNull()
                        if (hit == null) {
                            warnings.add("آهنگ «${track.title}» پیدا نشد")
                            continue
                        }
                        val niceName = FileNames.sanitize(
                            listOf(track.artists, track.title)
                                .filter { it.isNotBlank() }
                                .joinToString(" - "),
                            "spotify"
                        ) + ".m4a"
                        requests.add(
                            NewDownload(
                                url = hit.url,
                                fileName = niceName,
                                engine = EngineKind.MEDIA,
                                preset = "audio",
                                mimeType = "audio/mp4",
                                extractor = "Spotify",
                                durationSec = hit.durationSec
                            )
                        )
                        added++
                    }
                    if (added > 0) {
                        warnings.add("اسپاتیفای «${collection.name}»: $added آهنگ به صف اضافه شد.")
                    }
                }

                EngineKind.MEDIA -> {
                    onStatus("بررسی: $link")
                    val probe = engine.probe(link)
                    if (!probe.ok) {
                        // Keep it in the queue anyway: the downloader may still succeed (cookies,
                        // retries, or a transient block) and the error is shown per item.
                        requests.add(
                            NewDownload(url = link, engine = EngineKind.MEDIA, preset = preset)
                        )
                        warnings.add(probe.error ?: "استخراج اطلاعات ناموفق بود")
                        continue
                    }
                    if (probe.isPlaylist && probe.entries.isNotEmpty()) {
                        onStatus("پلی‌لیست: ${probe.title} (${probe.entries.size})")
                        for (entry in probe.entries) {
                            if (requests.size >= MAX_ITEMS) break
                            val name = FileNames.sanitize(
                                entry.title.ifBlank { "media" },
                                "media"
                            )
                            requests.add(
                                NewDownload(
                                    url = entry.url,
                                    fileName = "$name.mp4",
                                    engine = EngineKind.MEDIA,
                                    preset = preset,
                                    mimeType = "video/mp4",
                                    extractor = probe.extractor,
                                    durationSec = entry.durationSec
                                )
                            )
                        }
                        if (probe.entries.size > 1) {
                            warnings.add(
                                "پلی‌لیست «${probe.title}»: ${probe.entries.size} مورد پیدا شد."
                            )
                        }
                    } else {
                        val title = probe.title.ifBlank { "media" }
                        val ext = if (preset.equals("audio", true)) "m4a" else "mp4"
                        requests.add(
                            NewDownload(
                                url = link,
                                fileName = FileNames.sanitize(title, "media") + "." + ext,
                                engine = EngineKind.MEDIA,
                                preset = preset,
                                mimeType = if (preset.equals("audio", true)) "audio/mp4" else "video/mp4",
                                extractor = probe.extractor,
                                durationSec = probe.durationSec,
                                height = probe.bestHeight
                            )
                        )
                    }
                }
            }
            } catch (t: Throwable) {
                // One broken site must never abort the whole batch: report and keep going.
                warnings.add("بررسی این لینک ممکن نشد: " + link + " (" + t.javaClass.simpleName + ")")
            }
        }
        return Report(requests, warnings, skipped)
    }

    /** Short quality label used in the UI. */
    fun presetLabel(preset: String): String = when (preset.lowercase(Locale.US)) {
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        "audio" -> "صدا"
        else -> "بهترین"
    }
}
