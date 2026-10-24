package ir.aria.downloader.ui

import ir.aria.downloader.R

/**
 * Small platform badge shown on top of thumbnails in the queue and the
 * recent-links row, mirroring the Aria 3.0 mockup.
 */
object PlatformBadge {

    data class Badge(val symbol: String, val background: Int, val textColor: Int)

    fun of(url: String?): Badge {
        val link = url?.lowercase().orEmpty()
        return when {
            "youtu" in link -> Badge("▶", R.drawable.bg_badge_circle, R.color.aria_youtube)
            "instagram" in link -> Badge("📷", R.drawable.bg_badge_circle, R.color.aria_instagram)
            "spotify" in link -> Badge("🎵", R.drawable.bg_badge_circle, R.color.aria_spotify)
            "pornhub" in link -> Badge("PH", R.drawable.bg_badge_circle_dark, R.color.aria_pornhub)
            "tiktok" in link -> Badge("🎬", R.drawable.bg_badge_circle, R.color.aria_text)
            "soundcloud" in link -> Badge("🎧", R.drawable.bg_badge_circle, R.color.aria_text)
            "aparat" in link -> Badge("▶", R.drawable.bg_badge_circle, R.color.aria_primary)
            else -> Badge("🔗", R.drawable.bg_badge_circle, R.color.aria_text_dim)
        }
    }
}
