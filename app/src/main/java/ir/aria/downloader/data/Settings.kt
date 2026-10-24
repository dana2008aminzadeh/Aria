package ir.aria.downloader.data

import android.content.Context
import android.content.SharedPreferences
import ir.aria.downloader.net.HttpDownloader

/** User preferences backed by SharedPreferences. */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("aria_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_FRAGMENT_CONCURRENCY = "fragment_concurrency"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_AUTO_RETRY = "auto_retry"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_DEFAULT_PRESET = "default_preset"
        const val KEY_PROXY_URL = "proxy_url"
        const val DEFAULT_CONCURRENCY = 3
        const val MAX_CONCURRENCY = 10
        const val DEFAULT_FRAGMENT_CONCURRENCY = 6
        const val MAX_FRAGMENT_CONCURRENCY = 8
    }

    var concurrency: Int
        get() = prefs.getInt(KEY_CONCURRENCY, DEFAULT_CONCURRENCY).coerceIn(1, MAX_CONCURRENCY)
        set(value) = prefs.edit().putInt(KEY_CONCURRENCY, value.coerceIn(1, MAX_CONCURRENCY)).apply()

    /** Parallel HLS/DASH fragments per item; independent from the queue concurrency. */
    var fragmentConcurrency: Int
        get() = prefs.getInt(KEY_FRAGMENT_CONCURRENCY, DEFAULT_FRAGMENT_CONCURRENCY)
            .coerceIn(1, MAX_FRAGMENT_CONCURRENCY)
        set(value) = prefs.edit()
            .putInt(KEY_FRAGMENT_CONCURRENCY, value.coerceIn(1, MAX_FRAGMENT_CONCURRENCY))
            .apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var autoRetry: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RETRY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RETRY, value).apply()

    var userAgent: String
        get() = prefs.getString(KEY_USER_AGENT, HttpDownloader.DEFAULT_USER_AGENT)
            ?: HttpDownloader.DEFAULT_USER_AGENT
        set(value) = prefs.edit().putString(KEY_USER_AGENT, value).apply()

    /**
     * yt-dlp has current browser headers for each extractor. Do not replace them with the
     * native downloader's old Android UA unless the user explicitly supplied a custom one.
     */
    val mediaUserAgentOverride: String
        get() = userAgent.trim().takeIf {
            it.isNotEmpty() && it != HttpDownloader.DEFAULT_USER_AGENT
        }.orEmpty()

    /** Quality preset used only as a legacy/failure fallback; new items get exact choices. */
    var defaultPreset: String
        get() = prefs.getString(KEY_DEFAULT_PRESET, "best") ?: "best"
        set(value) = prefs.edit().putString(KEY_DEFAULT_PRESET, value).apply()

    /** Optional HTTP(S)/SOCKS5 proxy URL, including optional user:password authority. */
    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY_URL, "").orEmpty().trim()
        set(value) = prefs.edit().putString(KEY_PROXY_URL, value.trim()).apply()
}
