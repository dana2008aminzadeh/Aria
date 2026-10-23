package ir.aria.downloader

import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.UrlClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlClassifierTest {

    @Test
    fun directFilesGoToTheHttpEngine() {
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("https://cdn.example.com/a/b/movie.mp4"))
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("https://example.com/archive.zip?token=1"))
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("http://example.com/doc.pdf"))
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("https://example.com/pic%20one.jpg"))
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("https://example.com/app.apk"))
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("https://example.com/video.m3u8"))
    }

    @Test
    fun mediaHostsGoToTheExtractor() {
        val media = listOf(
            "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
            "https://youtu.be/aqz-KE-bpKQ",
            "https://m.youtube.com/shorts/abc123",
            "https://www.instagram.com/reel/CqLM8bIrGRK/",
            "https://www.tiktok.com/@user/video/12345",
            "https://twitter.com/user/status/123",
            "https://x.com/user/status/123",
            "https://soundcloud.com/artist/track",
            "https://vimeo.com/123456",
            "https://www.reddit.com/r/videos/comments/x/y/",
            "https://archive.org/details/BigBuckBunny_124",
            "https://www.aparat.com/v/abcd",
            "https://www.pornhub.com/view_video.php?viewkey=x",
            "https://www.dailymotion.com/video/x8abcd",
            "https://www.facebook.com/watch/?v=123"
        )
        for (url in media) {
            assertEquals("$url should use the extractor", EngineKind.MEDIA, UrlClassifier.classify(url))
        }
    }

    @Test
    fun spotifyLinksAreDetected() {
        assertEquals(EngineKind.SPOTIFY, UrlClassifier.classify("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals(EngineKind.SPOTIFY, UrlClassifier.classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("track", UrlClassifier.spotifyType("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals("playlist", UrlClassifier.spotifyType("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("4cOdK2wGLETKBW3PvgPWqT", UrlClassifier.spotifyId("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=1"))
    }

    @Test
    fun htmlPagesAreNotTreatedAsFiles() {
        assertFalse(UrlClassifier.isDirectFile("https://example.com/index.html"))
        assertFalse(UrlClassifier.isDirectFile("https://example.com/page.php?id=2"))
        assertEquals(EngineKind.MEDIA, UrlClassifier.classify("https://example.com/watch/some-video"))
    }

    @Test
    fun unknownHostWithFileExtensionIsADirectFile() {
        assertTrue(UrlClassifier.isDirectFile("https://random-site.xyz/files/report.docx"))
        assertEquals(EngineKind.HTTP, UrlClassifier.classify("https://random-site.xyz/files/report.docx"))
    }

    @Test
    fun mediaHostWithCdnFileLinkStillUsesHttp() {
        // e.g. a signed CDN link copied out of a player
        assertEquals(
            EngineKind.HTTP,
            UrlClassifier.classify("https://rr3---sn-abc.googlevideo.com/videoplayback/final.mp4?token=1")
        )
    }

    @Test
    fun helperMethods() {
        assertEquals("youtube.com", UrlClassifier.hostOf("https://www.youtube.com/watch?v=1"))
        assertEquals("mp4", UrlClassifier.extensionOf("https://a.com/b/c.MP4?x=1"))
        assertEquals("", UrlClassifier.extensionOf("https://a.com/b/c"))
        assertTrue(UrlClassifier.isKnownMediaHost("https://music.youtube.com/watch?v=1"))
        assertFalse(UrlClassifier.isKnownMediaHost("https://example.com"))
    }
}
