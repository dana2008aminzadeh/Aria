package ir.aria.downloader.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Channel creation and real system-level notification availability checks. */
object NotificationSupport {
    const val CHANNEL_PROGRESS = "aria_progress"
    const val CHANNEL_FINISHED = "aria_finished"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val progress = NotificationChannel(
            CHANNEL_PROGRESS, "پیشرفت دانلود", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "نمایش دانلودهای در حال انجام"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        val finished = NotificationChannel(
            CHANNEL_FINISHED, "پایان دانلود", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "اعلان پایان یا خطای دانلود"
            setShowBadge(true)
        }
        manager.createNotificationChannel(progress)
        manager.createNotificationChannel(finished)
    }

    fun hasRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun areAppNotificationsEnabled(context: Context): Boolean =
        hasRuntimePermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun isChannelEnabled(context: Context, channelId: String): Boolean {
        if (!areAppNotificationsEnabled(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(channelId) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Finished/failed alerts are optional; foreground progress must still be maintained. */
    fun canPostResultNotifications(context: Context): Boolean =
        isChannelEnabled(context, CHANNEL_FINISHED)

    fun openSystemSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)
            )
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
