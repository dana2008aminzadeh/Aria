package ir.aria.downloader

import ir.aria.downloader.engine.SpotifyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of the public Spotify embed payload. The fixture is the exact JSON shape that
 * open.spotify.com/embed/... served when this feature was implemented.
 */
class SpotifyResolverTest {

    private val trackEmbed = """
        <!DOCTYPE html><html><head><title>Spotify Embed</title></head><body>
        <script id="__NEXT_DATA__" type="application/json">{
          "props": {"pageProps": {"state": {"data": {"entity": {
            "type": "track",
            "name": "Never Gonna Give You Up",
            "title": "Never Gonna Give You Up",
            "subtitle": "Rick Astley",
            "duration": 213573,
            "isPlayable": true
          }}}}},
          "page": "/embed/track/4cOdK2wGLETKBW3PvgPWqT"
        }</script></body></html>
    """.trimIndent()

    private val playlistEmbed = """
        <html><body>
        <script id="__NEXT_DATA__" type="application/json">{
          "props": {"pageProps": {"state": {"data": {"entity": {
            "type": "playlist",
            "title": "Today’s Top Hits",
            "subtitle": "50 songs",
            "trackList": [
              {"title": "expectations (feat. Chappell Roan)", "subtitle": "Olivia Rodrigo, Chappell Roan", "duration": 213573},
              {"title": "Nicole Kidman", "subtitle": "ADÉLA"},
              {"title": "Stop The Wedding!", "subtitle": "Ashe"},
              {"title": "", "subtitle": "broken entry without title"},
              {"title": "Patient Zero", "subtitle": "Taylor Swift"}
            ]
          }}}}},
          "page": "/embed/playlist/37i9dQZF1DXcBWIGoYBM5M"
        }</script></body></html>
    """.trimIndent()

    @Test
    fun parsesSingleTrackEmbed() {
        val result = SpotifyResolver().parseEmbed(trackEmbed)
        assertTrue(result.ok)
        assertEquals("Never Gonna Give You Up", result.name)
        assertEquals(1, result.tracks.size)
        val track = result.tracks.first()
        assertEquals("Never Gonna Give You Up", track.title)
        assertEquals("Rick Astley", track.artists)
        assertTrue(track.query.contains("Rick Astley"))
        assertTrue(track.query.contains("Never Gonna Give You Up"))
        assertEquals(214L, track.durationSec)
        assertEquals(213L, SpotifyResolver.durationSeconds(213200L))
        assertEquals(null, SpotifyResolver.durationSeconds(0L))
    }

    @Test
    fun parsesPlaylistAndSkipsBrokenEntries() {
        val result = SpotifyResolver().parseEmbed(playlistEmbed)
        assertTrue(result.ok)
        assertEquals("Today’s Top Hits", result.name)
        assertEquals(4, result.tracks.size)
        assertEquals("expectations (feat. Chappell Roan)", result.tracks[0].title)
        assertEquals("Olivia Rodrigo, Chappell Roan", result.tracks[0].artists)
        assertEquals("Patient Zero", result.tracks.last().title)
    }

    @Test
    fun failsGracefullyWithoutNextData() {
        val result = SpotifyResolver().parseEmbed("<html><body>nothing here</body></html>")
        assertFalse(result.ok)
        assertTrue(result.error!!.isNotBlank())
    }

    @Test
    fun failsGracefullyOnBrokenJson() {
        val broken = """<script id="__NEXT_DATA__" type="application/json">{ not valid json </script>"""
        val result = SpotifyResolver().parseEmbed(broken)
        assertFalse(result.ok)
    }

    @Test
    fun buildsCleanedSearchQueries() {
        val withFeat = SpotifyResolver.buildQuery("Song Name (feat. Someone)", "Artist A, Artist B")
        assertEquals("Artist A Song Name audio", withFeat)
        val plain = SpotifyResolver.buildQuery("Song Name", "Artist")
        assertEquals("Artist Song Name audio", plain)
        val noArtist = SpotifyResolver.buildQuery("Song", "")
        assertEquals("Song audio", noArtist)
        assertEquals("Artist With You audio", SpotifyResolver.buildQuery("With You", "Artist"))
        assertEquals("AC/DC Song audio", SpotifyResolver.buildQuery("Song", "AC/DC"))
    }

    @Test
    fun parsesReportedClandestinaSingleAlbum() {
        val html = """
            <script id="__NEXT_DATA__" type="application/json">{
              "props": {"pageProps": {"state": {"data": {"entity": {
                "type": "album", "title": "Clandestina (Cover)",
                "trackList": [{
                  "title": "Clandestina (Cover)",
                  "subtitle": "Emma Peters, Edmofo Remix",
                  "duration": 213000
                }]
              }}}}}
            }</script>
        """.trimIndent()

        val result = SpotifyResolver().parseEmbed(html)

        assertTrue(result.ok)
        assertEquals(1, result.tracks.size)
        assertEquals("Clandestina (Cover)", result.tracks.single().title)
        assertEquals("Emma Peters, Edmofo Remix", result.tracks.single().artists)
        assertEquals(213L, result.tracks.single().durationSec)
        assertEquals(
            "Emma Peters Clandestina (Cover) audio",
            result.tracks.single().query
        )
    }

    @Test
    fun findsTrackMetadataWhenEmbedWrapperChanges() {
        val html = """
            <script id="__NEXT_DATA__" type="application/json">{
              "runtime": {"payload": {"entities": [{
                "type": "track", "name": "A Song",
                "artists": ["Artist A", {"name": "Artist B"}],
                "durationMs": 200500
              }]}}
            }</script>
        """.trimIndent()

        val result = SpotifyResolver().parseEmbed(html)

        assertTrue(result.ok)
        assertEquals("A Song", result.tracks.single().title)
        assertEquals("Artist A, Artist B", result.tracks.single().artists)
        assertEquals(201L, result.tracks.single().durationSec)
    }

    @Test
    fun singleTrackFallsBackToOEmbedWhenEmbedIsUnavailable() {
        val requests = mutableListOf<String>()
        val resolver = SpotifyResolver(fetcher = { url ->
            requests += url
            if (url.contains("/embed/")) throw IllegalStateException("embed unavailable")
            """{"title":"Fallback Song","author_name":"Fallback Artist"}"""
        })

        val result = resolver.resolve("https://open.spotify.com/track/abc123")

        assertTrue(result.ok)
        assertEquals("Fallback Song", result.tracks.single().title)
        assertEquals("Fallback Artist", result.tracks.single().artists)
        assertEquals(2, requests.size)
        assertTrue(requests.last().contains("/oembed?url="))
    }

    @Test
    fun rejectsUnsupportedSpotifyLinkTypesWithoutNetwork() {
        val resolver = SpotifyResolver(connectTimeoutMs = 1000, readTimeoutMs = 1000)
        val episode = resolver.resolve("https://open.spotify.com/episode/abc123")
        assertFalse(episode.ok)
        assertTrue(episode.error!!.contains("پادکست"))

        val invalid = resolver.resolve("https://open.spotify.com/track")
        assertFalse(invalid.ok)
    }
}
