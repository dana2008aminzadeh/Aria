package ir.aria.downloader

import ir.aria.downloader.engine.FormatOptions
import ir.aria.downloader.engine.ExtractorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatOptionsTest {

    private fun video(
        id: String,
        ext: String,
        codec: String,
        height: Int = 1080,
        progressive: Boolean = false
    ) = ExtractorEngine.FormatRow(
        id = id, ext = ext, height = height, fps = 30.0, filesize = 1_000_000,
        audioOnly = false, progressive = progressive, note = "",
        videoOnly = !progressive, hasAudio = progressive, hasVideo = true,
        vcodec = codec, acodec = if (progressive) "mp4a.40.2" else null
    )

    private fun audio(id: String, ext: String, codec: String, bitrate: Double) =
        ExtractorEngine.FormatRow(
            id = id, ext = ext, height = 0, fps = null, filesize = 100_000,
            audioOnly = true, progressive = false, note = "", videoOnly = false,
            hasAudio = true, hasVideo = false, abr = bitrate, acodec = codec
        )

    @Test
    fun vp9IsPairedWithOpusAndDefaultsToWebm() {
        val choices = FormatOptions.choices(listOf(
            video("248", "webm", "vp09.00.10.08"),
            audio("140", "m4a", "mp4a.40.2", 129.0),
            audio("251", "webm", "opus", 160.0)
        ))
        val vp9 = choices.single { it.format.id == "248" }
        assertEquals("251", vp9.pairedAudio?.id)

        val first = FormatOptions.outputs(vp9).first()
        assertEquals("webm", first.ext)
        assertEquals("video", first.kind)
        assertEquals("251", first.selection(vp9).audioFormatId)
    }

    @Test
    fun audioOutputFromSeparatePairUsesRealMp3Selection() {
        val choice = FormatOptions.choices(listOf(
            video("313", "webm", "vp09", 2160), audio("251", "webm", "opus", 160.0)
        )).first { it.format.id == "313" }
        val mp3 = FormatOptions.outputs(choice).single {
            it.ext == "mp3" && it.bitrateKbps == 192
        }.selection(choice)

        assertEquals("313", mp3.formatId)
        assertEquals("251", mp3.audioFormatId)
        assertEquals("audio", mp3.outputKind)
        assertEquals(192, mp3.audioBitrateKbps)
    }

    @Test
    fun spotifyModeDoesNotChooseProgressiveVideoWhenAudioExists() {
        val choices = FormatOptions.choices(listOf(
            video("18", "mp4", "avc1", 360, progressive = true),
            audio("251", "webm", "opus", 160.0),
            audio("140", "m4a", "mp4a.40.2", 129.0)
        ), audioOnly = true)

        assertEquals(listOf("140", "251"), choices.map { it.format.id })
        assertFalse(choices.any { it.format.progressive })
        assertTrue(FormatOptions.outputs(choices.first(), audioOnly = true)
            .all { it.kind == "audio" })
    }

    @Test
    fun progressiveAndSilentModesRemainAvailable() {
        val progressive = FormatOptions.choices(listOf(
            video("18", "mp4", "avc1", 360, progressive = true)
        )).single()
        assertNull(progressive.pairedAudio)
        assertTrue(FormatOptions.outputs(progressive).any { it.ext == "mkv" })
        assertTrue(FormatOptions.outputs(progressive).any { it.ext == "mp3" })

        val silent = FormatOptions.choices(listOf(video("137", "mp4", "avc1"))).single()
        val output = FormatOptions.outputs(silent).single()
        assertEquals("video_only", output.kind)
        assertNull(output.selection(silent).audioFormatId)
    }

    @Test
    fun everyRealRowGetsAChoiceWithoutCodecFiltering() {
        val rows = listOf(
            video("av1", "mp4", "av01", 2160),
            video("vp8", "webm", "vp8", 480),
            video("h264", "mp4", "avc1", 720),
            audio("aac", "m4a", "mp4a", 128.0)
        )
        assertEquals(4, FormatOptions.choices(rows).size)
        assertFalse(FormatOptions.choices(rows).any { it.format.id == "missing" })
    }
}
