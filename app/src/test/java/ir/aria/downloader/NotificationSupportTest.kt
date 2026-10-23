package ir.aria.downloader

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ir.aria.downloader.service.NotificationSupport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationSupportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        NotificationSupport.createChannels(context)
    }

    @Test
    fun createsIndependentProgressAndResultChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(manager.getNotificationChannel(NotificationSupport.CHANNEL_PROGRESS))
        assertNotNull(manager.getNotificationChannel(NotificationSupport.CHANNEL_FINISHED))
        assertTrue(NotificationSupport.isChannelEnabled(context, NotificationSupport.CHANNEL_PROGRESS))
        assertTrue(NotificationSupport.canPostResultNotifications(context))
    }

    @Test
    fun blockingResultChannelDoesNotBlockForegroundProgress() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finished = manager.getNotificationChannel(NotificationSupport.CHANNEL_FINISHED)
        finished.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(finished)

        assertFalse(NotificationSupport.canPostResultNotifications(context))
        assertTrue(NotificationSupport.isChannelEnabled(context, NotificationSupport.CHANNEL_PROGRESS))
    }
}
