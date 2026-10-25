package ir.aria.downloader

import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.MediaUrlNormalizer
import ir.aria.downloader.engine.UrlClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUrlNormalizerTest {

    @Test
    fun aparatShareAndEmbedLinksBecomeCanonicalVideoLinks() {
        val expected = "https://www.aparat.com/v/8dflw"
        assertEquals(expected, MediaUrlNormalizer.normalize("http://aparat.com/v/8dflw/?utm_source=share"))
        assertEquals(
            expected,
            MediaUrlNormalizer.normalize(
                "https://www.aparat.com/video/video/embed/videohash/8dflw/"
            )
        )
        assertEquals(
            expected,
            MediaUrlNormalizer.normalize(
                "https://www.aparat.com/video/video/embed/vt/frame/showvideo/yes/videohash/8dflw"
            )
        )
    }

    @Test
    fun aparatIdIsCaseSensitiveAndInvalidPathsAreLeftAlone() {
        assertEquals(
            "https://www.aparat.com/v/wP8On",
            MediaUrlNormalizer.normalize("https://www.aparat.com/v/wP8On")
        )
        val channel = "https://www.aparat.com/channel/example"
        assertEquals(channel, MediaUrlNormalizer.normalize(channel))
    }

    @Test
    fun pornhubViewEmbedLocalizedAndPremiumLinksKeepTheirVideoId() {
        assertEquals(
            "https://www.pornhub.com/view_video.php?viewkey=ph5af5fef7c2aa7",
            MediaUrlNormalizer.normalize(
                "https://fr.pornhub.com/view_video.php?viewkey=ph5af5fef7c2aa7&utm_source=share"
            )
        )
        assertEquals(
            "https://www.pornhub.com/view_video.php?viewkey=648719015",
            MediaUrlNormalizer.normalize("https://www.pornhub.com/embed/648719015")
        )
        assertEquals(
            "https://www.pornhubpremium.com/view_video.php?viewkey=ph5e4acdae54a82",
            MediaUrlNormalizer.normalize(
                "https://www.pornhubpremium.com/video/show?viewkey=ph5e4acdae54a82"
            )
        )
    }

    @Test
    fun allPornhubDomainsUseTheMediaEngine() {
        val urls = listOf(
            "https://www.pornhub.net/view_video.php?viewkey=203640933",
            "https://www.pornhub.org/view_video.php?viewkey=203640933",
            "https://www.pornhubpremium.com/view_video.php?viewkey=ph5e4acdae54a82"
        )
        for (url in urls) {
            assertEquals(EngineKind.MEDIA, UrlClassifier.classify(url))
        }
    }

    @Test
    fun instagramPostAndReelLinksKeepTheirIdAndDropTracking() {
        assertEquals(
            "https://www.instagram.com/reel/CqLM8bIrGRK/",
            MediaUrlNormalizer.normalize("https://instagr.am/reels/CqLM8bIrGRK/?igsh=share")
        )
        assertEquals(
            "https://www.instagram.com/p/aye83DjauH/",
            MediaUrlNormalizer.normalize("https://www.instagram.com/p/aye83DjauH/?foo=bar")
        )
        val shareToken = "https://www.instagram.com/share/reel/ABC123"
        assertEquals(shareToken, MediaUrlNormalizer.normalize(shareToken))
        val audio = "https://www.instagram.com/reels/audio/12345"
        assertEquals(audio, MediaUrlNormalizer.normalize(audio))
    }

    @Test
    fun redditShortAndCommentLinksBecomeCanonical() {
        assertEquals(
            "https://www.reddit.com/comments/abc123/",
            MediaUrlNormalizer.normalize("https://redd.it/abc123")
        )
        assertEquals(
            "https://www.reddit.com/comments/abc123/",
            MediaUrlNormalizer.normalize("https://old.reddit.com/r/videos/comments/abc123/some-title/?utm=share")
        )
        val share = "https://www.reddit.com/r/videos/s/AbC123xYz"
        assertEquals(share, MediaUrlNormalizer.normalize(share))
    }

    @Test
    fun pinterestAnyTldAndVimeoPlayerLinksBecomeCanonical() {
        assertEquals(
            "https://www.pinterest.com/pin/123456789/",
            MediaUrlNormalizer.normalize("https://www.pinterest.co.uk/pin/123456789/?utm_source=share")
        )
        assertEquals(
            "https://vimeo.com/123456789",
            MediaUrlNormalizer.normalize("https://player.vimeo.com/video/123456789?h=abc")
        )
        assertEquals(
            "https://www.twitch.tv/videos/123456789",
            MediaUrlNormalizer.normalize("https://m.twitch.tv/videos/123456789")
        )
    }

    @Test
    fun unrelatedLinksAreNotModified() {
        val url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=10"
        assertEquals(url, MediaUrlNormalizer.normalize(url))
    }
}
