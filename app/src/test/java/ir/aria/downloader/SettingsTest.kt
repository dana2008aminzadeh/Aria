package ir.aria.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ir.aria.downloader.data.Settings
import ir.aria.downloader.net.HttpDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsTest {

    private lateinit var context: Context
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("aria_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = Settings(context)
    }

    @Test
    fun defaultsAreSane() {
        assertEquals(Settings.DEFAULT_CONCURRENCY, settings.concurrency)
        assertEquals(Settings.DEFAULT_FRAGMENT_CONCURRENCY, settings.fragmentConcurrency)
        assertFalse(settings.wifiOnly)
        assertTrue(settings.notificationsEnabled)
        assertTrue(settings.autoRetry)
        assertEquals("best", settings.defaultPreset)
        assertEquals(HttpDownloader.DEFAULT_USER_AGENT, settings.userAgent)
    }

    @Test
    fun valuesPersistAcrossInstances() {
        settings.concurrency = 7
        settings.fragmentConcurrency = 6
        settings.wifiOnly = true
        settings.notificationsEnabled = false
        settings.autoRetry = false
        settings.defaultPreset = "1080"
        settings.userAgent = "CustomAgent/2.0"

        val reopened = Settings(context)
        assertEquals(7, reopened.concurrency)
        assertEquals(6, reopened.fragmentConcurrency)
        assertTrue(reopened.wifiOnly)
        assertFalse(reopened.notificationsEnabled)
        assertFalse(reopened.autoRetry)
        assertEquals("1080", reopened.defaultPreset)
        assertEquals("CustomAgent/2.0", reopened.userAgent)
    }

    @Test
    fun concurrencyIsClampedToSafeRange() {
        settings.concurrency = 0
        assertEquals(1, settings.concurrency)
        settings.concurrency = 99
        assertEquals(Settings.MAX_CONCURRENCY, settings.concurrency)
        settings.concurrency = -5
        assertEquals(1, settings.concurrency)
        settings.fragmentConcurrency = 0
        assertEquals(1, settings.fragmentConcurrency)
        settings.fragmentConcurrency = 99
        assertEquals(Settings.MAX_FRAGMENT_CONCURRENCY, settings.fragmentConcurrency)
    }
}
