package ir.aria.downloader.util

import java.util.Locale
import java.util.concurrent.TimeUnit

/** Human readable formatting helpers (pure JVM). */
object Formats {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < UNITS.size - 1) {
            size /= 1024.0
            unit++
        }
        return if (size >= 100) {
            String.format(Locale.US, "%.0f %s", size, UNITS[unit])
        } else {
            String.format(Locale.US, "%.1f %s", size, UNITS[unit])
        }
    }

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return ""
        return bytes(bytesPerSecond) + "/s"
    }

    fun eta(seconds: Long): String {
        if (seconds <= 0 || seconds > TimeUnit.DAYS.toSeconds(9)) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> "${s}s"
        }
    }

    /** mm:ss / h:mm:ss for media durations. */
    fun duration(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun percent(downloaded: Long, total: Long): Int {
        if (total <= 0) return 0
        return ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
    }
}
