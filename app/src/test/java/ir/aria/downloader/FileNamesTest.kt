package ir.aria.downloader

import ir.aria.downloader.net.FileNames
import ir.aria.downloader.net.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun sanitizeReplacesIllegalCharacters() {
        assertEquals("a_b_c_.mp4", FileNames.sanitize("a/b:c?.mp4"))
        assertEquals("file", FileNames.sanitize("   ", fallback = "file"))
        assertEquals("name", FileNames.sanitize("...name..."))
    }

    @Test
    fun sanitizeKeepsReasonableLength() {
        val long = "x".repeat(300) + ".mp4"
        val clean = FileNames.sanitize(long)
        assertTrue(clean.length <= 140)
        assertTrue(clean.endsWith(".mp4"))
    }

    @Test
    fun derivesNameFromUrl() {
        assertEquals("movie.mp4", FileNames.fromUrl("https://cdn.site.com/a/b/movie.mp4?token=1"))
        assertEquals("my file.mp4", FileNames.fromUrl("https://cdn.site.com/my%20file.mp4"))
        assertNull(FileNames.fromUrl("https://cdn.site.com/"))
        assertNull(FileNames.fromUrl("https://cdn.site.com/download"))
    }

    @Test
    fun parsesContentDispositionVariants() {
        assertEquals("clip.mp4", FileNames.fromContentDisposition("attachment; filename=\"clip.mp4\""))
        assertEquals("clip.mp4", FileNames.fromContentDisposition("attachment; filename=clip.mp4"))
        assertEquals(
            "فیلم من.mp4",
            FileNames.fromContentDisposition("attachment; filename*=UTF-8''%D9%81%DB%8C%D9%84%D9%85%20%D9%85%D9%86.mp4")
        )
        assertEquals(
            "clip.mp4",
            FileNames.fromContentDisposition("attachment; filename=\"fallback.mp4\"; filename*=UTF-8''clip.mp4")
        )
        assertNull(FileNames.fromContentDisposition(null))
        assertNull(FileNames.fromContentDisposition("inline"))
    }

    @Test
    fun uniqueNamesGetSuffix() {
        val taken = setOf("video.mp4", "video (1).mp4")
        assertEquals("video (2).mp4", FileNames.ensureUnique("video.mp4", taken))
        assertEquals("fresh.mp4", FileNames.ensureUnique("fresh.mp4", taken))
    }

    @Test
    fun extensionAndMimeGuessing() {
        assertEquals("mp4", FileNames.extensionOf("A.B.MP4"))
        assertEquals("video/x-matroska", FileNames.guessMime("a.mkv"))
        assertEquals("application/pdf", FileNames.guessMime("book.pdf"))
        assertEquals("audio/mp4", FileNames.guessMime("track.m4a"))
    }

    @Test
    fun mimeNormalisationPrefersHeaderButFallsBackToExtension() {
        assertEquals("video/mp4", MimeTypes.normalize("video/mp4", "a.mp4"))
        assertEquals("video/mp4", MimeTypes.normalize("application/octet-stream", "a.mp4"))
        assertEquals("audio/mpeg", MimeTypes.normalize("text/html", "song.mp3"))
        assertEquals("mp4", MimeTypes.extensionForMime("video/mp4; charset=binary"))
        assertFalse(MimeTypes.normalize("text/html", "page.unknownext") == "text/html")
    }

    @Test
    fun categoryDetection() {
        assertEquals(MimeTypes.Category.VIDEO, MimeTypes.categoryOf("video/mp4", "a.mp4"))
        assertEquals(MimeTypes.Category.AUDIO, MimeTypes.categoryOf("audio/mpeg", "a.mp3"))
        assertEquals(MimeTypes.Category.IMAGE, MimeTypes.categoryOf("image/png", "a.png"))
        assertEquals(MimeTypes.Category.ARCHIVE, MimeTypes.categoryOf("application/zip", "a.zip"))
        assertEquals(MimeTypes.Category.APP, MimeTypes.categoryOf("application/vnd.android.package-archive", "a.apk"))
        assertEquals(MimeTypes.Category.OTHER, MimeTypes.categoryOf("application/octet-stream", "a.bin"))
    }

    @Test
    fun outputExtensionCannotBeShadowedByMediaTitle() {
        assertEquals("Movie.mp3", FileNames.withExtension("Movie.mp4", "mp3"))
        assertEquals("Song.m4a", FileNames.withExtension("Song.mp3", "m4a"))
        assertEquals("Episode.2024.mkv", FileNames.withExtension("Episode.2024", "mkv"))
    }

    @Test
    fun persianAndEmojiNamesArePreserved() {
        val name = FileNames.sanitize("آهنگ جدید 🎵 (ریمیکس) [128k].m4a")
        assertTrue(name.contains("آهنگ جدید"))
        assertTrue(name.endsWith(".m4a"))
        assertFalse(name.contains("/"))
    }
}
