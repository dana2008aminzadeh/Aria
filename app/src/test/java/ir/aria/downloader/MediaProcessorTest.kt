package ir.aria.downloader

import ir.aria.downloader.net.MediaCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Verifies real FFmpeg arguments without requiring native Android libraries on the JVM. */
class MediaProcessorTest {

    @Test
    fun mergeUsesArgumentArrayAndLosslessCopy() {
        val video = File("/tmp/video with space.webm")
        val audio = File("/tmp/audio 'quoted'.webm")
        val output = File("/tmp/final output.webm")
        val args = MediaCommands.merge(video, audio, output)

        assertTrue(args.contains(video.absolutePath))
        assertTrue(args.contains(audio.absolutePath))
        assertTrue(args.containsAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy")))
        assertEquals(output.absolutePath, args.last())
        assertFalse(args.contains("+faststart"))
    }

    @Test
    fun audioConversionUsesRealCodecAndBitrate() {
        val args = MediaCommands.audio(
            File("/tmp/input.m4a"), File("/tmp/output.mp3"), "mp3", 192
        )
        val codecIndex = args.indexOf("-c:a")
        val bitrateIndex = args.indexOf("-b:a")
        assertEquals("libmp3lame", args[codecIndex + 1])
        assertEquals("192k", args[bitrateIndex + 1])
        assertTrue(args.contains("-vn"))
    }

    @Test
    fun losslessOutputsDoNotGetFakeBitrate() {
        val flac = MediaCommands.audio(
            File("/tmp/input.webm"), File("/tmp/output.flac"), "flac", 320
        )
        assertFalse(flac.contains("-b:a"))
        assertTrue(flac.contains("flac"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownAudioExtensionIsRejectedInsteadOfRenamed() {
        MediaCommands.audio(
            File("/tmp/input.mp4"), File("/tmp/output.exe"), "exe", 0
        )
    }
}
