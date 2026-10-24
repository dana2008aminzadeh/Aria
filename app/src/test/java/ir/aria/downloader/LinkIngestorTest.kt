package ir.aria.downloader

import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.ExtractorApi
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.engine.LinkIngestor
import ir.aria.downloader.engine.SpotifyResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Verifies how pasted links become queue items, without touching the network. */
class LinkIngestorTest {

    private class FakeEngine(
        private val probes: Map<String, ExtractorEngine.Probe> = emptyMap(),
        private val searchResults: Map<String, List<ExtractorEngine.SearchHit>> = emptyMap(),
        private val searchErrors: Map<String, String> = emptyMap()
    ) : ExtractorApi {
        var probeCalls = 0
        var searchCalls = 0

        override suspend fun probe(url: String): ExtractorEngine.Probe {
            probeCalls++
            return probes[url] ?: ExtractorEngine.Probe(false, "not found")
        }

        override suspend fun resolve(url: String, preset: String) =
            ExtractorEngine.Plan(false, error = "not used")

        override suspend fun search(query: String, limit: Int): ExtractorEngine.SearchResponse {
            searchCalls++
            return ExtractorEngine.SearchResponse(
                searchResults[query].orEmpty(), searchErrors[query]
            )
        }

        override suspend fun download(
            url: String,
            outDir: File,
            formatId: String?,
            preset: String,
            shouldCancel: () -> Boolean,
            onProgress: (ExtractorEngine.Progress) -> Unit
        ) = ExtractorEngine.Outcome(false, error = "not used")

        override suspend fun info() = ExtractorEngine.EngineInfo("test", "test", "test")

        override fun hasCookies() = false
    }

    @Test
    fun directFileLinksBecomeHttpItems() = runBlocking {
        val ingestor = LinkIngestor(FakeEngine())
        val report = ingestor.ingest(
            listOf("https://cdn.example.com/movie.mp4", "https://example.com/doc.pdf"),
            preset = "1080"
        )
        assertEquals(2, report.requests.size)
        assertTrue(report.requests.all { it.engine == EngineKind.HTTP })
        assertEquals("movie.mp4", report.requests[0].fileName)
        assertEquals("video/mp4", report.requests[0].mimeType)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun singleMediaPageUsesProbedTitleAndPreset() = runBlocking {
        val url = "https://www.youtube.com/watch?v=abc"
        val engine = FakeEngine(
            probes = mapOf(
                url to ExtractorEngine.Probe(
                    ok = true, title = "My Video", uploader = "chan", durationSec = 120,
                    extractor = "Youtube", formats = emptyList(), bestHeight = 1080
                )
            )
        )
        val report = LinkIngestor(engine).ingest(listOf(url), preset = "720")

        assertEquals(1, report.requests.size)
        val request = report.requests.single()
        assertEquals(EngineKind.MEDIA, request.engine)
        assertEquals("720", request.preset)
        assertEquals("My Video.mp4", request.fileName)
        assertEquals("video/mp4", request.mimeType)
        assertEquals("Youtube", request.extractor)
        assertEquals(120L, request.durationSec)
    }

    @Test
    fun audioPresetUsesM4aName() = runBlocking {
        val url = "https://soundcloud.com/artist/track"
        val engine = FakeEngine(
            probes = mapOf(url to ExtractorEngine.Probe(ok = true, title = "A Track", extractor = "Soundcloud"))
        )
        val report = LinkIngestor(engine).ingest(listOf(url), preset = "audio")
        val request = report.requests.single()
        assertEquals("A Track.m4a", request.fileName)
        assertEquals("audio/mp4", request.mimeType)
        assertEquals("audio", request.preset)
    }

    @Test
    fun playlistIsExpandedIntoIndividualItems() = runBlocking {
        val url = "https://www.youtube.com/playlist?list=PL123"
        val engine = FakeEngine(
            probes = mapOf(
                url to ExtractorEngine.Probe(
                    ok = true,
                    title = "My Playlist",
                    isPlaylist = true,
                    extractor = "Youtube",
                    entries = listOf(
                        ExtractorEngine.PlaylistEntry("First Song", "https://youtu.be/1", 60),
                        ExtractorEngine.PlaylistEntry("Second Song", "https://youtu.be/2", 90),
                        ExtractorEngine.PlaylistEntry("Third Song", "https://youtu.be/3", 30)
                    )
                )
            )
        )
        val report = LinkIngestor(engine).ingest(listOf(url), preset = "best")

        assertEquals(3, report.requests.size)
        assertTrue(report.requests.all { it.engine == EngineKind.MEDIA })
        assertEquals(listOf("https://youtu.be/1", "https://youtu.be/2", "https://youtu.be/3"),
            report.requests.map { it.url })
        assertEquals("First Song.mp4", report.requests[0].fileName)
        assertTrue(report.warnings.any { it.contains("پلی‌لیست") })
    }

    @Test
    fun failedProbeStillQueuesTheLinkWithAWarning() = runBlocking {
        val url = "https://www.instagram.com/reel/xyz/"
        val engine = FakeEngine(probes = mapOf(url to ExtractorEngine.Probe(false, "این محتوا نیاز به ورود دارد.")))
        val report = LinkIngestor(engine).ingest(listOf(url), preset = "best")

        assertEquals(1, report.requests.size)
        assertEquals(EngineKind.MEDIA, report.requests.single().engine)
        assertTrue(report.warnings.single().contains("ورود"))
    }

    @Test
    fun spotifyPlaylistIsMatchedAgainstTheStreamEngine() = runBlocking {
        val spotifyUrl = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        val engine = FakeEngine(
            searchResults = mapOf(
                "Rick Astley Never Gonna Give You Up audio" to listOf(
                    ExtractorEngine.SearchHit("Never Gonna Give You Up", "https://youtu.be/dQw4w9WgXcQ", 213, "Rick Astley")
                ),
                "Ashe Stop The Wedding! audio" to listOf(
                    ExtractorEngine.SearchHit("Stop The Wedding!", "https://youtu.be/xyz", 200, "Ashe")
                )
            )
        )
        val collection = SpotifyResolver.Collection(
            ok = true,
            name = "Mix",
            tracks = listOf(
                SpotifyResolver.Track("Never Gonna Give You Up", "Rick Astley", "Rick Astley Never Gonna Give You Up audio"),
                SpotifyResolver.Track("Stop The Wedding!", "Ashe", "Ashe Stop The Wedding! audio"),
                SpotifyResolver.Track("Unavailable Song", "Nobody", "Nobody Unavailable Song audio")
            )
        )
        val report = LinkIngestor(engine, spotifyLookup = { collection }).ingest(listOf(spotifyUrl), preset = "best")

        assertEquals(2, report.requests.size)
        assertTrue(report.requests.all { it.engine == EngineKind.MEDIA && it.preset == "audio" })
        assertEquals("Rick Astley - Never Gonna Give You Up.m4a", report.requests[0].fileName)
        assertEquals("https://youtu.be/dQw4w9WgXcQ", report.requests[0].url)
        assertEquals("Spotify", report.requests[0].extractor)
        assertTrue("missing track should be reported", report.warnings.any { it.contains("Unavailable Song") })
    }

    @Test
    fun reportedClandestinaCoverIsAcceptedWithRemixArtistName() = runBlocking {
        val albumUrl = "https://open.spotify.com/album/4MXxuWsvppJvR2K4sgx8bz?si=share"
        val track = SpotifyResolver.Track(
            title = "Clandestina (Cover)",
            artists = "Emma Peters, Edmofo Remix",
            query = "Emma Peters Clandestina (Cover) audio",
            durationSec = 213
        )
        val engine = FakeEngine(searchResults = mapOf(track.query to listOf(
            ExtractorEngine.SearchHit(
                "Clandestina (Cover)", "https://youtu.be/UTgnOwpafpw",
                214, "Emma Péters & Edmofo Remix - Topic"
            )
        )))
        val collection = SpotifyResolver.Collection(true, "Clandestina (Cover)", listOf(track))

        val report = LinkIngestor(engine, spotifyLookup = { collection })
            .ingest(listOf(albumUrl), preset = "audio")

        assertEquals(1, report.requests.size)
        assertEquals("https://youtu.be/UTgnOwpafpw", report.requests.single().url)
        assertEquals(1, engine.searchCalls)
        assertTrue(report.warnings.none { it.contains("مطمئن") })
    }

    @Test
    fun brokenSpotifyLinkProducesOnlyAWarning() = runBlocking {
        val report = LinkIngestor(
            FakeEngine(),
            spotifyLookup = { SpotifyResolver.Collection(false, "", emptyList(), "لینک اسپاتیفای معتبر نیست") }
        ).ingest(listOf("https://open.spotify.com/track/abc"), preset = "audio")

        assertTrue(report.isEmpty)
        assertEquals("لینک اسپاتیفای معتبر نیست", report.warnings.single())
    }

    @Test
    fun interactiveSelectionIsStoredWithExactOutput() = runBlocking {
        val url = "https://www.youtube.com/watch?v=choose"
        val formats = listOf(
            ExtractorEngine.FormatRow("1080", "mp4", 1080, null, 1000, false, true, ""),
            ExtractorEngine.FormatRow("720", "mp4", 720, null, 800, false, true, "")
        )
        val engine = FakeEngine(probes = mapOf(url to ExtractorEngine.Probe(
            ok = true, title = "Choose Me", formats = formats, extractor = "Youtube"
        )))
        val selection = ExtractorEngine.FormatSelection(
            formatId = "720", outputExt = "mkv", outputKind = "video", label = "720p • MKV"
        )
        val report = LinkIngestor(engine).ingest(
            listOf(url), preset = "best", chooser = { request ->
                assertFalse(request.audioOnly)
                assertEquals(2, request.probe.formats.size)
                selection
            }
        )

        val request = report.requests.single()
        assertEquals(selection, request.selection)
        assertEquals("Choose Me.mkv", request.fileName)
        assertEquals("video/x-matroska", request.mimeType)
        assertEquals(720, request.height)
    }

    @Test
    fun interactivePlaylistProbesAndChoosesEveryEntry() = runBlocking {
        val playlistUrl = "https://www.youtube.com/playlist?list=choose"
        val oneUrl = "https://youtu.be/choose1"
        val twoUrl = "https://youtu.be/choose2"
        fun probe(title: String) = ExtractorEngine.Probe(
            ok = true, title = title, formats = listOf(
                ExtractorEngine.FormatRow(title, "mp4", 720, null, 1000, false, true, "")
            )
        )
        val engine = FakeEngine(probes = mapOf(
            playlistUrl to ExtractorEngine.Probe(
                ok = true, title = "Queue", isPlaylist = true, entries = listOf(
                    ExtractorEngine.PlaylistEntry("One", oneUrl, 10),
                    ExtractorEngine.PlaylistEntry("Two", twoUrl, 20)
                )
            ),
            oneUrl to probe("One"), twoUrl to probe("Two")
        ))
        val report = LinkIngestor(engine).ingest(
            listOf(playlistUrl), preset = "best", chooser = { request ->
                ExtractorEngine.FormatSelection(
                    request.probe.formats.single().id, outputKind = "video"
                )
            }
        )

        assertEquals(listOf("One", "Two"), report.requests.map { it.selection?.formatId })
        assertEquals(3, engine.probeCalls)
    }

    @Test
    fun uncertainSpotifyMatchIsSkippedInsteadOfDownloadingDifferentSong() = runBlocking {
        val spotifyUrl = "https://open.spotify.com/track/uncertain"
        val query = "Artist Song audio"
        val engine = FakeEngine(searchResults = mapOf(query to listOf(
            ExtractorEngine.SearchHit("Completely Different", "https://youtu.be/wrong", 30, "Other")
        )))
        val collection = SpotifyResolver.Collection(
            true, "Track", listOf(SpotifyResolver.Track("Song", "Artist", query))
        )

        val report = LinkIngestor(engine, spotifyLookup = { collection })
            .ingest(listOf(spotifyUrl), preset = "best")

        assertTrue(report.requests.isEmpty())
        assertTrue(report.warnings.any { it.contains("مطمئن") })
        assertEquals(2, engine.searchCalls)
    }

    @Test
    fun spotifySearchFailureIsNotHiddenBehindGenericNotFoundWarning() = runBlocking {
        val spotifyUrl = "https://open.spotify.com/track/search-error"
        val query = "Artist Song audio"
        val precise = SpotifyResolver.preciseQuery("Song", "Artist")
        val engine = FakeEngine(searchErrors = mapOf(
            query to "یوتیوب درخواست را مسدود کرد (بررسی ربات)",
            precise to "یوتیوب درخواست را مسدود کرد (بررسی ربات)"
        ))
        val collection = SpotifyResolver.Collection(
            true, "Track", listOf(SpotifyResolver.Track("Song", "Artist", query))
        )

        val report = LinkIngestor(engine, spotifyLookup = { collection })
            .ingest(listOf(spotifyUrl), preset = "best")

        assertTrue(report.requests.isEmpty())
        assertTrue(report.warnings.any { it.contains("خطای جست‌وجو") && it.contains("بررسی ربات") })
    }

    @Test
    fun interactiveSpotifyUsesAudioOnlyChooserAndSelectedExtension() = runBlocking {
        val spotifyUrl = "https://open.spotify.com/track/choose"
        val hitUrl = "https://youtu.be/audio-choice"
        val query = "Artist Song audio"
        val engine = FakeEngine(
            probes = mapOf(hitUrl to ExtractorEngine.Probe(
                ok = true, title = "Source", formats = listOf(
                    ExtractorEngine.FormatRow("a", "m4a", 0, null, 100, true, false, "")
                )
            )),
            searchResults = mapOf(query to listOf(
                ExtractorEngine.SearchHit("Song", hitUrl, 30, "Artist")
            ))
        )
        val collection = SpotifyResolver.Collection(
            true, "Track", listOf(SpotifyResolver.Track("Song", "Artist", query))
        )
        val selection = ExtractorEngine.FormatSelection(
            "a", outputExt = "mp3", outputKind = "audio", audioBitrateKbps = 320
        )
        val report = LinkIngestor(engine, spotifyLookup = { collection }).ingest(
            listOf(spotifyUrl), preset = "best", chooser = { request ->
                assertTrue(request.audioOnly)
                assertEquals("Artist - Song", request.displayTitle)
                selection
            }
        )

        val request = report.requests.single()
        assertEquals("Artist - Song.mp3", request.fileName)
        assertEquals("audio/mpeg", request.mimeType)
        assertEquals("audio", request.preset)
        assertEquals("Spotify", request.extractor)
        assertEquals(selection, request.selection)
    }

    @Test
    fun respectsTheItemCap() = runBlocking {
        val entries = (1..200).map {
            ExtractorEngine.PlaylistEntry("Item $it", "https://youtu.be/$it", 10)
        }
        val url = "https://www.youtube.com/playlist?list=huge"
        val engine = FakeEngine(
            probes = mapOf(url to ExtractorEngine.Probe(ok = true, title = "Huge", isPlaylist = true, entries = entries))
        )
        val report = LinkIngestor(engine).ingest(listOf(url), preset = "best")
        assertEquals(LinkIngestor.MAX_ITEMS, report.requests.size)
        assertTrue(report.warnings.any { it.contains("پلی‌لیست") })
    }

    @Test
    fun mixedLinksAreAllClassifiedCorrectly() = runBlocking {
        val mediaUrl = "https://vimeo.com/12345"
        val engine = FakeEngine(
            probes = mapOf(mediaUrl to ExtractorEngine.Probe(ok = true, title = "Vimeo Clip", extractor = "Vimeo"))
        )
        val report = LinkIngestor(engine).ingest(
            listOf("https://example.com/a.zip", mediaUrl, "https://example.com/b.png"),
            preset = "best"
        )
        assertEquals(3, report.requests.size)
        assertEquals(EngineKind.HTTP, report.requests[0].engine)
        assertEquals(EngineKind.MEDIA, report.requests[1].engine)
        assertEquals(EngineKind.HTTP, report.requests[2].engine)
        assertFalse(report.requests.any { it.url.isBlank() })
    }

    @Test
    fun aBrokenLinkDoesNotAbortTheRestOfTheBatch() = runBlocking {
        val exploding = object : ExtractorApi {
            override suspend fun probe(url: String): ExtractorEngine.Probe = throw IllegalStateException("native crash")
            override suspend fun resolve(url: String, preset: String) = ExtractorEngine.Plan(false, error = "x")
            override suspend fun search(query: String, limit: Int) =
                ExtractorEngine.SearchResponse(emptyList())
            override suspend fun download(
                url: String, outDir: File, formatId: String?, preset: String,
                shouldCancel: () -> Boolean, onProgress: (ExtractorEngine.Progress) -> Unit
            ) = ExtractorEngine.Outcome(false, error = "x")
            override suspend fun info() = ExtractorEngine.EngineInfo("x", "x", "x")
            override fun hasCookies() = false
        }
        val report = LinkIngestor(exploding).ingest(
            listOf("https://cdn.example.com/ok.mp4", "https://youtube.com/watch?v=x", "https://cdn.example.com/ok2.zip"),
            preset = "best"
        )
        // the two direct links must survive even though the media probe blew up
        assertEquals(2, report.requests.size)
        assertEquals(EngineKind.HTTP, report.requests[0].engine)
        assertEquals(EngineKind.HTTP, report.requests[1].engine)
        assertTrue("the failure must be reported", report.warnings.any { it.contains("بررسی") })
    }

    @Test
    fun aThrowingSpotifyLookupProducesAWarningOnly() = runBlocking {
        val report = LinkIngestor(
            FakeEngine(),
            spotifyLookup = { throw IllegalStateException("network on main thread") }
        ).ingest(listOf("https://open.spotify.com/track/abc"), preset = "audio")
        assertTrue(report.isEmpty)
        assertTrue(report.warnings.isNotEmpty())
    }
}
