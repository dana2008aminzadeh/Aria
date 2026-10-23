package ir.aria.downloader

import ir.aria.downloader.net.Mp4Remuxer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mp4parser.muxer.container.mp4.MovieCreator
import java.io.File
import java.nio.file.Files

/**
 * Remux tests with **real DASH files**: the fixtures are a 144p H.264 video-only stream and a
 * 48 kbit AAC audio-only stream downloaded from a DASH source, i.e. exactly what yt-dlp produces
 * on a phone. This is the same code path the service uses to build the final MP4.
 */
class Mp4RemuxerTest {

    private fun fixture(name: String): File {
        val stream = javaClass.classLoader!!.getResourceAsStream("media/$name")
            ?: error("missing fixture $name")
        val temp = File.createTempFile("fixture", "-$name")
        temp.deleteOnExit()
        stream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        return temp
    }

    private fun tempTarget(): File {
        // path must NOT exist yet: the remuxer creates it itself and deletes it on failure
        val dir = Files.createTempDirectory("aria-remux").toFile()
        dir.deleteOnExit()
        return File(dir, "merged.mp4").also { it.deleteOnExit() }
    }

    @Test
    fun fixtureFilesAreValidMp4() {
        assertTrue(Mp4Remuxer.looksLikeMp4(fixture("video_144p.mp4")))
        assertTrue(Mp4Remuxer.looksLikeMp4(fixture("audio_48k.m4a")))
    }

    @Test
    fun detectsNonMp4Content() {
        val text = File.createTempFile("not-media", ".mp4").apply {
            writeText("this is definitely not an mp4 file, just plain text padding")
            deleteOnExit()
        }
        assertFalse(Mp4Remuxer.looksLikeMp4(text))
        assertFalse(Mp4Remuxer.looksLikeMp4(File("/does/not/exist.mp4")))
    }

    @Test
    fun mergesSeparateVideoAndAudioIntoOnePlayableMp4() {
        val video = fixture("video_144p.mp4")
        val audio = fixture("audio_48k.m4a")
        val target = tempTarget()

        val result = Mp4Remuxer.remux(video, audio, target)

        assertTrue("merged file should exist", result.file.exists())
        assertTrue("merged file should be smaller than both inputs together",
            result.file.length() <= video.length() + audio.length() + 64 * 1024)
        assertTrue("merged file should be bigger than the video alone",
            result.file.length() > video.length())

        // the real proof: re-open the container and verify both media tracks survived
        val movie = MovieCreator.build(result.file.absolutePath)
        val handlers = movie.tracks.map { it.handler?.lowercase() }
        assertEquals("exactly one video track", 1, handlers.count { it == "vide" })
        assertEquals("exactly one audio track", 1, handlers.count { it == "soun" })
        assertEquals(2, movie.tracks.size)

        val videoTrack = movie.tracks.first { it.handler.equals("vide", true) }
        val audioTrack = movie.tracks.first { it.handler.equals("soun", true) }
        assertTrue("video samples copied", videoTrack.samples.isNotEmpty())
        assertTrue("audio samples copied", audioTrack.samples.isNotEmpty())
        assertTrue("duration should be reported", result.durationMs > 1000)

        // sample counts must match the sources byte-for-byte (lossless copy, no re-encode)
        val sourceVideo = MovieCreator.build(video.absolutePath).tracks.first()
        val sourceAudio = MovieCreator.build(audio.absolutePath).tracks.first()
        assertEquals(sourceVideo.samples.size, videoTrack.samples.size)
        assertEquals(sourceAudio.samples.size, audioTrack.samples.size)
        assertEquals("vide", result.videoTrack.lowercase())
        assertEquals("soun", result.audioTrack.lowercase())
    }

    @Test
    fun rejectsAudioThatIsNotMp4() {
        val video = fixture("video_144p.mp4")
        val fakeAudio = File.createTempFile("fake", ".m4a").apply {
            writeText("ID3 this is an mp3, not an m4a container at all........")
            deleteOnExit()
        }
        val target = tempTarget()
        try {
            Mp4Remuxer.remux(video, fakeAudio, target)
            fail("expected RemuxException")
        } catch (expected: Mp4Remuxer.RemuxException) {
            assertNotNull(expected.message)
            assertFalse("no broken file should be left behind", target.exists())
        }
    }

    @Test
    fun rejectsMissingVideo() {
        val target = tempTarget()
        try {
            Mp4Remuxer.remux(File("/nope/video.mp4"), fixture("audio_48k.m4a"), target)
            fail("expected RemuxException")
        } catch (expected: Mp4Remuxer.RemuxException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun worksWhenOnlyVideoIsGiven() {
        val video = fixture("video_144p.mp4")
        val target = tempTarget()
        val result = Mp4Remuxer.remux(video, File("/missing/audio.m4a"), target)
        assertTrue(result.file.exists())
        val handlers = MovieCreator.build(result.file.absolutePath).tracks.map { it.handler?.lowercase() }
        assertTrue(handlers.contains("vide"))
    }
}
