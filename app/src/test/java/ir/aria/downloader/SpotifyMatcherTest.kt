package ir.aria.downloader

import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.engine.SpotifyMatcher
import ir.aria.downloader.engine.SpotifyResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyMatcherTest {
    private fun track(
        title: String = "Never Gonna Give You Up",
        artists: String = "Rick Astley",
        duration: Long? = 213L
    ) = SpotifyResolver.Track(title, artists, SpotifyResolver.buildQuery(title, artists), duration)

    private fun hit(
        title: String,
        artist: String = "Rick Astley",
        duration: Long? = 213L,
        id: String = title
    ) = ExtractorEngine.SearchHit(title, "https://youtu.be/$id", duration, artist)

    @Test
    fun originalTopicUploadBeatsCoverEvenWithSameTitleAndDuration() {
        val original = hit("Never Gonna Give You Up", "Rick Astley - Topic", id = "original")
        val cover = hit("Never Gonna Give You Up (Cover)", "Best Covers", id = "cover")

        val match = SpotifyMatcher.bestMatch(track(), listOf(cover, original))

        assertEquals("original", match?.hit?.url?.substringAfterLast('/'))
        assertTrue(SpotifyMatcher.score(track(), original) > SpotifyMatcher.score(track(), cover))
    }

    @Test
    fun durationBreaksTieBetweenSameTitleUploads() {
        val correct = hit("Never Gonna Give You Up", duration = 213, id = "correct")
        val wrongDuration = hit("Never Gonna Give You Up", duration = 90, id = "wrong")

        val match = SpotifyMatcher.bestMatch(track(), listOf(wrongDuration, correct))

        assertEquals("correct", match?.hit?.url?.substringAfterLast('/'))
    }

    @Test
    fun differentSongBySameArtistIsNotAcceptedJustBecauseDurationMatches() {
        val wrong = hit("Another Song", duration = 213)
        val target = track(title = "Song Name")

        assertNull(SpotifyMatcher.bestMatch(target, listOf(wrong)))
    }

    @Test
    fun requestedRemixDoesNotFallBackToOriginal() {
        val target = track(title = "Song (Remix)")
        val original = hit("Song")
        val remix = hit("Song (Remix)", id = "remix")

        assertNull(SpotifyMatcher.bestMatch(target, listOf(original)))
        assertEquals("remix", SpotifyMatcher.bestMatch(target, listOf(original, remix))
            ?.hit?.url?.substringAfterLast('/'))
    }

    @Test
    fun originalDoesNotFallBackToLiveKaraokeOrSpedUpVersions() {
        val target = track(title = "Song")
        val alternatives = listOf(
            hit("Song Live", id = "live"),
            hit("Song Karaoke", id = "karaoke"),
            hit("Song Sped Up", id = "sped")
        )

        assertNull(SpotifyMatcher.bestMatch(target, alternatives))
    }

    @Test
    fun unicodeArtistNamesAndOfficialChannelsStillMatch() {
        val target = track(title = "Halo", artists = "Beyoncé", duration = 261)
        val candidate = hit("Halo (Official Audio)", "Beyonce Official", 261, "halo")

        val match = SpotifyMatcher.bestMatch(target, listOf(candidate))

        assertEquals("halo", match?.hit?.url?.substringAfterLast('/'))
        assertTrue((match?.score ?: 0) >= SpotifyMatcher.MIN_CONFIDENCE)
    }

    @Test
    fun remixInArtistNameDoesNotRejectClandestinaCover() {
        val target = track(
            title = "Clandestina (Cover)",
            artists = "Emma Peters, Edmofo Remix",
            duration = 213
        )
        val official = hit(
            title = "Clandestina (Cover)",
            artist = "Emma Péters & Edmofo Remix - Topic",
            duration = 214,
            id = "UTgnOwpafpw"
        )

        val match = SpotifyMatcher.bestMatch(target, listOf(official))

        assertEquals("UTgnOwpafpw", match?.hit?.url?.substringAfterLast('/'))
        assertTrue((match?.score ?: 0) >= SpotifyMatcher.MIN_CONFIDENCE)
    }

    @Test
    fun actualRemixTitleIsStillRejectedWhenArtistNameAlsoContainsRemix() {
        val target = track(
            title = "Clandestina (Cover)",
            artists = "Emma Peters, Edmofo Remix",
            duration = 213
        )
        val actualRemix = hit(
            title = "Clandestina (Cover) (Edmofo Remix)",
            artist = "Emma Péters & Edmofo Remix - Topic",
            duration = 213
        )

        assertNull(SpotifyMatcher.bestMatch(target, listOf(actualRemix)))
    }

    @Test
    fun genericCoverChannelIsStillRejectedWhenRequestedArtistIsMissing() {
        val target = track(title = "Song", artists = "Real Artist", duration = 213)
        val cover = hit("Song", artist = "Best Covers", duration = 213)

        assertNull(SpotifyMatcher.bestMatch(target, listOf(cover)))
    }

    @Test
    fun emptyAndBlankCandidatesAreIgnored() {
        assertNull(SpotifyMatcher.bestMatch(track(), emptyList()))
        assertNull(SpotifyMatcher.bestMatch(track(), listOf(hit("Song").copy(url = ""))))
    }
}
