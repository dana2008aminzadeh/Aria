package ir.aria.downloader

import ir.aria.downloader.net.UrlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlExtractorTest {

    @Test
    fun extractsLinksFromMessyChatText() {
        val text = """
            سلام، اینا رو دانلود کن:
            https://example.com/a.mp4
            look at (https://example.com/b.jpg), and https://example.com/c.zip.
            https://example.com/a.mp4
        """.trimIndent()
        val urls = UrlExtractor.extract(text)
        assertEquals(listOf("https://example.com/a.mp4", "https://example.com/b.jpg", "https://example.com/c.zip"), urls)
    }

    @Test
    fun stripsTrailingPunctuationAndQuotes() {
        assertEquals("https://example.com/file.pdf", UrlExtractor.clean("https://example.com/file.pdf)."))
        assertEquals("https://example.com/x", UrlExtractor.clean("\"https://example.com/x\""))
        assertEquals("https://example.com/y", UrlExtractor.clean("https://example.com/y،"))
    }

    @Test
    fun ignoresNonHttpSchemesAndInvalidHosts() {
        assertTrue(UrlExtractor.extract("ftp://example.com/a.mp4 file:///sdcard/a.mp4 magnet:?xt=urn:btih:x").isEmpty())
        assertFalse(UrlExtractor.isValid("http://"))
        assertFalse(UrlExtractor.isValid("not a url"))
        assertTrue(UrlExtractor.isValid("https://sub.example.co.uk/path/file.mp4?token=abc#frag"))
    }

    @Test
    fun expandsSimpleNumericRange() {
        val result = UrlExtractor.expandLine("https://host/img{1-4}.jpg")
        assertEquals(
            listOf(
                "https://host/img1.jpg",
                "https://host/img2.jpg",
                "https://host/img3.jpg",
                "https://host/img4.jpg"
            ),
            result
        )
    }

    @Test
    fun expandsZeroPaddedRange() {
        val result = UrlExtractor.expandLine("https://host/s{01-03}/a.png")
        assertEquals(
            listOf("https://host/s01/a.png", "https://host/s02/a.png", "https://host/s03/a.png"),
            result
        )
    }

    @Test
    fun expandsDescendingRange() {
        val result = UrlExtractor.expandLine("https://host/{3-1}.txt")
        assertEquals(listOf("https://host/3.txt", "https://host/2.txt", "https://host/1.txt"), result)
    }

    @Test
    fun expandsCartesianProductOfTwoRanges() {
        val result = UrlExtractor.expandLine("https://host/{1-2}/{1-2}.bin")
        assertEquals(4, result.size)
        assertTrue(result.contains("https://host/1/1.bin"))
        assertTrue(result.contains("https://host/2/2.bin"))
    }

    @Test
    fun parseValidatesAndDeduplicatesPatterns() {
        val text = """
            https://a.com/{1-2}.jpg
            https://a.com/1.jpg
            https://a.com/bad.jpg
            not-a-link
        """.trimIndent()
        val urls = UrlExtractor.parse(text)
        assertEquals(listOf("https://a.com/1.jpg", "https://a.com/2.jpg", "https://a.com/bad.jpg"), urls)
    }

    @Test
    fun respectsLimit() {
        val urls = UrlExtractor.expandLine("https://host/{1-5000}.jpg", limit = 100)
        assertEquals(100, urls.size)
    }

    @Test
    fun keepsQueryStringsIntact() {
        val urls = UrlExtractor.parse("https://cdn.example.com/v.mp4?token=abc123&expires=99")
        assertEquals(listOf("https://cdn.example.com/v.mp4?token=abc123&expires=99"), urls)
    }

    @Test
    fun youtubeAndSpotifyLinksSurviveParsing() {
        val text = "https://www.youtube.com/watch?v=aqz-KE-bpKQ https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        assertEquals(
            listOf(
                "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
                "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
            ),
            UrlExtractor.parse(text)
        )
    }
}
