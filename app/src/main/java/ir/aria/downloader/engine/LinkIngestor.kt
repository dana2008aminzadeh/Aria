package ir.aria.downloader.engine

import ir.aria.downloader.data.NewDownload
import ir.aria.downloader.diag.DiagLog
import ir.aria.downloader.net.FileNames
import ir.aria.downloader.net.MimeTypes
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Turns arbitrary links into direct, media, playlist and Spotify queue requests. */
class LinkIngestor(
    private val engine: ExtractorApi,
    spotifyLookup: ((String) -> SpotifyResolver.Collection)? = null
) {

    // The app-wide DiagLog sink (logcat-only no-op outside diag builds); the default Spotify
    // resolver shares it so embed/oEmbed fetches appear in the same diagnostic timeline.
    private val spotifyLookup: (String) -> SpotifyResolver.Collection =
        spotifyLookup ?: SpotifyResolver(log = { DiagLog.d("Spotify", it) })::resolve

    private fun diag(message: String) = DiagLog.i("Ingest", message)

    companion object {
        const val MAX_ITEMS = 120
    }

    data class Report(
        val requests: List<NewDownload>,
        val warnings: List<String>,
        val skipped: Int = 0
    ) {
        val isEmpty: Boolean get() = requests.isEmpty()
    }

    data class ChoiceRequest(
        val probe: ExtractorEngine.Probe,
        val audioOnly: Boolean,
        val displayTitle: String
    )

    /**
     * If [chooser] is supplied, every successfully probed media item gets its own exact
     * format/output selection. Passing no chooser preserves the original preset behavior.
     */
    suspend fun ingest(
        links: List<String>,
        preset: String,
        chooser: (suspend (ChoiceRequest) -> ExtractorEngine.FormatSelection?)? = null,
        onStatus: (String) -> Unit = {}
    ): Report {
        val requests = ArrayList<NewDownload>()
        val warnings = ArrayList<String>()
        var skipped = 0
        diag(
            "ingest start: " + links.size + " link(s), preset=" + preset +
                ", chooser=" + (chooser != null)
        )

        for (rawLink in links) {
            val link = MediaUrlNormalizer.normalize(rawLink)
            diag(
                "link raw='" + DiagLog.redactUrl(rawLink) + "' normalized='" +
                    DiagLog.redactUrl(link) + "' kind=" + UrlClassifier.classify(link)
            )
            if (requests.size >= MAX_ITEMS) {
                skipped += links.size - requests.size
                warnings.add("به سقف $MAX_ITEMS مورد در هر بار رسیدید؛ بقیه لینک‌ها اضافه نشدند.")
                break
            }
            try {
                when (UrlClassifier.classify(link)) {
                    EngineKind.HTTP -> {
                        val name = FileNames.fromUrl(link)
                        requests.add(NewDownload(
                            url = link,
                            fileName = name,
                            engine = EngineKind.HTTP,
                            preset = preset,
                            mimeType = if (name != null) FileNames.guessMime(name) else "application/octet-stream"
                        ))
                    }

                    EngineKind.SPOTIFY -> {
                        onStatus("اسپاتیفای: خواندن اطلاعات…")
                        val collection = try {
                            spotifyLookup(link)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            SpotifyResolver.Collection(false, "", emptyList(), "اسپاتیفای قابل خواندن نبود")
                        }
                        if (!collection.ok) {
                            warnings.add(collection.error ?: "اسپاتیفای قابل خواندن نبود")
                            continue
                        }
                        var added = 0
                        for (track in collection.tracks) {
                            if (requests.size >= MAX_ITEMS) break
                            onStatus("اسپاتیفای: جست‌وجوی ${track.title}")
                            val primarySearch = engine.search(track.query, 8)
                            var candidates = primarySearch.hits
                            var searchError = primarySearch.error
                            var match = SpotifyMatcher.bestMatch(track, candidates)
                            diag(
                                "spotify track '" + track.title + "' by '" + track.artists +
                                    "' dur=" + track.durationSec + " query='" + track.query +
                                    "' candidates=" + candidates.size +
                                    " searchError='" + searchError + "'" +
                                    candidates.joinToString("") {
                                        "\n  cand '" + it.title + "' by '" + it.uploader + "' dur=" +
                                            it.durationSec + " score=" + SpotifyMatcher.score(track, it)
                                    } + "\n  chosen=" + (match?.let { "'" + it.hit.title + "' (" + it.score + ")" } ?: "none")
                            )
                            if (match == null) {
                                val preciseQuery = SpotifyResolver.preciseQuery(track.title, track.artists)
                                if (preciseQuery != track.query) {
                                    onStatus("تطبیق دقیق‌تر: ${track.title}")
                                    diag("spotify precise retry query='" + preciseQuery + "'")
                                    val preciseSearch = engine.search(preciseQuery, 5)
                                    candidates = candidates + preciseSearch.hits
                                    searchError = preciseSearch.error
                                    match = SpotifyMatcher.bestMatch(track, candidates)
                                }
                            }
                            val matched = match
                            if (matched == null) {
                                val detail = searchError?.takeIf { it.isNotBlank() }
                                    ?.let { "\nخطای جست‌وجوی منبع: $it" }.orEmpty()
                                warnings.add(
                                    "نتیجهٔ مطمئن برای آهنگ «${track.title}» پیدا نشد؛ " +
                                        "نتیجهٔ مشابه/اشتباه دانلود نمی‌شود.$detail"
                                )
                                continue
                            }
                            val hit = matched.hit
                            onStatus("تطبیق Spotify: ${hit.title}")
                            val displayTitle = listOf(track.artists, track.title)
                                .filter { it.isNotBlank() }.joinToString(" - ")
                            var request: NewDownload? = null
                            if (chooser != null) {
                                onStatus("فرمت‌های صوتی: ${track.title}")
                                val detail = engine.probe(hit.url)
                                if (detail.ok && !detail.isPlaylist && detail.formats.isNotEmpty()) {
                                    val selection = chooser(ChoiceRequest(detail, true, displayTitle))
                                    if (selection == null) {
                                        skipped++
                                        warnings.add("آهنگ «${track.title}» توسط کاربر اضافه نشد.")
                                        continue
                                    }
                                    request = selectedRequest(
                                        hit.url, detail, selection, displayTitle,
                                        "Spotify", hit.durationSec
                                    )
                                } else if (!detail.ok) {
                                    warnings.add("فرمت‌های «${track.title}» خوانده نشد؛ حالت پیش‌فرض در صف است. " +
                                        (detail.error.orEmpty()))
                                }
                            }
                            requests.add(request ?: legacySpotifyRequest(hit, track, displayTitle))
                            added++
                        }
                        if (added > 0) {
                            warnings.add("اسپاتیفای «${collection.name}»: $added آهنگ به صف اضافه شد.")
                        }
                    }

                    EngineKind.MEDIA -> {
                        onStatus("بررسی: $link")
                        val probe = engine.probe(link)
                        diag(
                            "media probe ok=" + probe.ok + " extractor=" + probe.extractor +
                                " title='" + probe.title + "' playlist=" + probe.isPlaylist +
                                " entries=" + probe.entries.size + " formats=" + probe.formats.size +
                                " error='" + probe.error + "'"
                        )
                        if (!probe.ok) {
                            // A transient/cookie failure can still be retried by the service.
                            requests.add(NewDownload(url = link, engine = EngineKind.MEDIA, preset = preset))
                            warnings.add(probe.error ?: "استخراج اطلاعات ناموفق بود")
                            continue
                        }
                        if (probe.isPlaylist && probe.entries.isNotEmpty()) {
                            onStatus("پلی‌لیست: ${probe.title} (${probe.entries.size})")
                            for (entry in probe.entries) {
                                if (requests.size >= MAX_ITEMS) break
                                var request: NewDownload? = null
                                if (chooser != null) {
                                    onStatus("فرمت‌ها: ${entry.title}")
                                    val detail = engine.probe(entry.url)
                                    if (detail.ok && !detail.isPlaylist && detail.formats.isNotEmpty()) {
                                        val selection = chooser(ChoiceRequest(detail, false, entry.title))
                                        if (selection == null) {
                                            skipped++
                                            warnings.add("«${entry.title}» توسط کاربر اضافه نشد.")
                                            continue
                                        }
                                        request = selectedRequest(
                                            entry.url, detail, selection, entry.title,
                                            probe.extractor, entry.durationSec
                                        )
                                    } else {
                                        warnings.add("فرمت‌های «${entry.title}» خوانده نشد؛ پیش‌فرض استفاده می‌شود. " +
                                            detail.error.orEmpty())
                                    }
                                }
                                requests.add(request ?: legacyPlaylistRequest(probe, entry, preset))
                            }
                            if (probe.entries.size > 1) {
                                warnings.add("پلی‌لیست «${probe.title}»: ${probe.entries.size} مورد پیدا شد.")
                            }
                        } else if (probe.formats.isEmpty() && !probe.directFileUrl.isNullOrBlank()) {
                            // Image-only post (Pinterest pin / Reddit / TikTok photo):
                            // there is no video stream, so save the image itself directly.
                            val imageUrl = probe.directFileUrl
                            val imageExt = UrlClassifier.extensionOf(imageUrl).ifBlank { "jpg" }
                            requests.add(
                                NewDownload(
                                    url = imageUrl,
                                    fileName = FileNames.sanitize(
                                        probe.title.ifBlank { "image" }, "image"
                                    ) + "." + imageExt,
                                    engine = EngineKind.HTTP,
                                    mimeType = when (imageExt) {
                                        "png" -> "image/png"
                                        "webp" -> "image/webp"
                                        "gif" -> "image/gif"
                                        else -> "image/jpeg"
                                    },
                                    extractor = probe.extractor
                                )
                            )
                            warnings.add("این پست فقط عکس است؛ خودِ عکس دانلود می‌شود.")
                        } else {
                            var request: NewDownload? = null
                            if (chooser != null && probe.formats.isNotEmpty()) {
                                val selection = chooser(ChoiceRequest(probe, false, probe.title))
                                if (selection == null) {
                                    skipped++
                                    warnings.add("«${probe.title.ifBlank { link }}» توسط کاربر اضافه نشد.")
                                    continue
                                }
                                request = selectedRequest(link, probe, selection, probe.title)
                            } else if (chooser != null) {
                                warnings.add("سایت فهرست فرمت نداد؛ برای «${probe.title}» پیش‌فرض استفاده می‌شود.")
                            }
                            requests.add(request ?: legacyMediaRequest(link, probe, preset))
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                DiagLog.e("Ingest", "link failed: " + DiagLog.redactUrl(link), t)
                warnings.add("بررسی این لینک ممکن نشد: " + link + " (" + t.javaClass.simpleName + ")")
            }
        }
        diag(
            "ingest done: requests=" + requests.size + " skipped=" + skipped +
                " warnings=" + warnings.size + warnings.joinToString("") { "\n  warn: $it" }
        )
        return Report(requests, warnings, skipped)
    }

    private fun legacyMediaRequest(
        url: String,
        probe: ExtractorEngine.Probe,
        preset: String
    ): NewDownload {
        val title = probe.title.ifBlank { "media" }
        val ext = if (preset.equals("audio", true)) "m4a" else "mp4"
        return NewDownload(
            url = url,
            fileName = FileNames.sanitize(title, "media") + "." + ext,
            engine = EngineKind.MEDIA,
            preset = preset,
            mimeType = if (preset.equals("audio", true)) "audio/mp4" else "video/mp4",
            extractor = probe.extractor,
            durationSec = probe.durationSec,
            height = probe.bestHeight
        )
    }

    private fun legacyPlaylistRequest(
        playlist: ExtractorEngine.Probe,
        entry: ExtractorEngine.PlaylistEntry,
        preset: String
    ): NewDownload {
        val name = FileNames.sanitize(entry.title.ifBlank { "media" }, "media")
        return NewDownload(
            url = entry.url,
            fileName = "$name.mp4",
            engine = EngineKind.MEDIA,
            preset = preset,
            mimeType = "video/mp4",
            extractor = playlist.extractor,
            durationSec = entry.durationSec
        )
    }

    private fun legacySpotifyRequest(
        hit: ExtractorEngine.SearchHit,
        track: SpotifyResolver.Track,
        displayTitle: String
    ): NewDownload {
        val niceName = FileNames.sanitize(displayTitle.ifBlank { track.title }, "spotify") + ".m4a"
        return NewDownload(
            url = hit.url,
            fileName = niceName,
            engine = EngineKind.MEDIA,
            preset = "audio",
            mimeType = "audio/mp4",
            extractor = "Spotify",
            durationSec = hit.durationSec
        )
    }

    private fun selectedRequest(
        url: String,
        probe: ExtractorEngine.Probe,
        selection: ExtractorEngine.FormatSelection,
        displayTitle: String,
        extractor: String = probe.extractor,
        durationSec: Long? = probe.durationSec
    ): NewDownload {
        val primary = probe.formats.firstOrNull { it.id == selection.formatId }
        val pairedAudio = probe.formats.firstOrNull { it.id == selection.audioFormatId }
        val ext = selection.outputExt?.takeIf { it.isNotBlank() } ?: when {
            selection.outputKind == "audio" -> (pairedAudio ?: primary)?.ext ?: "m4a"
            else -> primary?.ext ?: "mp4"
        }
        val title = displayTitle.ifBlank { probe.title }.ifBlank { "media" }
        return NewDownload(
            url = url,
            fileName = FileNames.sanitize(title, "media") + "." + ext,
            engine = EngineKind.MEDIA,
            preset = if (selection.outputKind == "audio") "audio" else "best",
            mimeType = MimeTypes.mimeForExtension(ext),
            extractor = extractor,
            durationSec = durationSec,
            height = if (selection.outputKind == "audio") 0 else primary?.height ?: 0,
            selection = selection
        )
    }

    fun presetLabel(preset: String): String = when (preset.lowercase(Locale.US)) {
        "1080" -> "1080p"
        "720" -> "720p"
        "480" -> "480p"
        "360" -> "360p"
        "audio" -> "صدا"
        else -> "بهترین"
    }
}
