package ir.aria.downloader.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log for the bug-fixing build: every important event (links added,
 * probe/resolve results, download attempts, retries, speeds, errors with detail)
 * is timestamped into filesDir/logs/alldown.log so the user can review it and
 * share it back for debugging. Rotates at ~2 MB, keeping one previous file.
 */
object AppLog {

    private const val MAX_BYTES = 2_000_000L
    private const val FILE_NAME = "alldown.log"
    private const val OLD_NAME = "alldown-old.log"

    @Volatile
    private var logDir: File? = null
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        init(File(context.filesDir, "logs"))
    }

    /** Separate entry point so plain JVM unit tests can use a temp directory. */
    fun init(dir: File) {
        synchronized(lock) {
            logDir = dir
        }
    }

    private fun file(): File? {
        val dir = logDir ?: return null
        return try {
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_NAME)
        } catch (e: Exception) {
            null
        }
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(" [").append(tag).append("] ")
            append(message.replace('\n', ' '))
            if (throwable != null) {
                append(" | ").append(throwable.javaClass.simpleName)
                append(": ").append(throwable.message ?: "")
            }
            append('\n')
        }
        synchronized(lock) {
            try {
                val target = file() ?: return
                if (target.exists() && target.length() > MAX_BYTES) {
                    val old = File(target.parentFile, OLD_NAME)
                    if (old.exists()) old.delete()
                    target.renameTo(old)
                }
                target.appendText(line)
            } catch (ignored: Exception) {
                // Logging must never break the app.
            }
        }
    }

    /** Full current log text (previous rotated file is prepended when present). */
    fun text(): String = synchronized(lock) {
        val dir = logDir ?: return ""
        val sb = StringBuilder()
        try {
            val old = File(dir, OLD_NAME)
            if (old.exists()) sb.append(old.readText())
            val current = File(dir, FILE_NAME)
            if (current.exists()) sb.append(current.readText())
        } catch (ignored: Exception) {
        }
        sb.toString()
    }

    /** The current log file, for sharing via FileProvider. */
    fun currentFile(): File? {
        val f = file()
        return if (f != null && f.exists() && f.length() > 0) f else null
    }

    fun clear() {
        synchronized(lock) {
            try {
                val dir = logDir ?: return
                File(dir, FILE_NAME).delete()
                File(dir, OLD_NAME).delete()
            } catch (ignored: Exception) {
            }
        }
        log("لاگ", "لاگ پاک شد")
    }
}
