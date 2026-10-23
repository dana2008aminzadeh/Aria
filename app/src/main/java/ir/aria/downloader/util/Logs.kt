package ir.aria.downloader.util

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device logger.
 *
 * The app can never be debugged with `adb logcat` by an end user, so everything that matters is
 * appended to a small text file inside the app's private folder. The user can open it from the
 * menu ("گزارش خطا / لاگ") and copy it. Uncaught exceptions are written to `crash.log` and shown on
 * the next launch, so a crash on a phone we do not own still produces a readable stack trace.
 */
object Logs {

    private const val MAX_BYTES = 256 * 1024
    private const val FILE_NAME = "app.log"
    private const val CRASH_NAME = "crash.log"
    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun logFile(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)
    private fun crashFile(context: Context): File = File(context.applicationContext.filesDir, CRASH_NAME)

    fun d(context: Context?, tag: String, message: String) {
        if (context == null) return
        append(context, "${stamp.format(Date())}  $tag  $message")
    }

    fun e(context: Context?, tag: String, message: String, error: Throwable? = null) {
        if (context == null) return
        val detail = if (error == null) message else "$message :: ${error.javaClass.simpleName}: ${error.message}"
        d(context, tag, "ERROR $detail")
        if (error != null) append(context, stack(error))
    }

    private fun append(context: Context, line: String) {
        synchronized(lock) {
            try {
                val file = logFile(context)
                if (file.exists() && file.length() > MAX_BYTES) {
                    // keep the newest half so the file never grows without bound
                    val text = file.readText()
                    file.writeText(text.substring(text.length / 2))
                }
                file.appendText(line + "\n")
            } catch (ignored: Throwable) {
            }
        }
    }

    fun stack(error: Throwable): String = try {
        val writer = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(writer))
        writer.toString()
    } catch (e: Throwable) {
        "cannot print stack: ${error.javaClass.name}"
    }

    /** Called from the uncaught exception handler: full context + stack, kept for the next start. */
    fun crash(context: Context?, thread: Thread?, error: Throwable) {
        if (context == null) return
        val info = buildString {
            appendLine("زمان: " + stamp.format(Date()))
            appendLine("نسخه: " + appVersion(context))
            appendLine("دستگاه: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            appendLine("آرک: " + Build.SUPPORTED_ABIS.joinToString(","))
            appendLine("رشته: " + (thread?.name ?: "-"))
            appendLine()
            append(stack(error))
        }
        try {
            crashFile(context).writeText(info)
        } catch (ignored: Throwable) {
        }
        append(context, "CRASH ${error.javaClass.name}: ${error.message}")
        append(context, stack(error))
    }

    private fun appVersion(context: Context): String = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: "?"
    } catch (e: Throwable) {
        "?"
    }

    /** The report of the previous run, or null when the app closed normally. */
    fun pendingCrash(context: Context): String? {
        return try {
            val file = crashFile(context)
            if (file.exists() && file.length() > 0) file.readText() else null
        } catch (e: Throwable) {
            null
        }
    }

    /** Call after the user has seen / copied the crash report. */
    fun acknowledgeCrash(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    fun read(context: Context, maxChars: Int = 20000): String {
        return try {
            val parts = ArrayList<String>()
            parts.add(
                "=== آریا دانلودر " + appVersion(context) + " / " +
                    Build.MANUFACTURER + " " + Build.MODEL + " / Android " +
                    Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") ==="
            )
            val crash = crashFile(context)
            if (crash.exists() && crash.length() > 0) parts.add("=== crash.log ===\n" + crash.readText())
            val log = logFile(context)
            if (log.exists()) parts.add("=== app.log ===\n" + log.readText())
            if (parts.isEmpty()) "" else parts.joinToString("\n\n").takeLast(maxChars)
        } catch (e: Throwable) {
            ""
        }
    }

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
        runCatching { crashFile(context).delete() }
    }

    // ------------------------------------------------------------------ session tracking

    private fun sessionFile(context: Context) = File(context.applicationContext.filesDir, "session.open")

    private fun versionFile(context: Context) = File(context.applicationContext.filesDir, "last.version")

    /** Set by [beginSession]: the run before this one did not shut down normally. */
    @Volatile
    var hadPreviousUncleanSession: Boolean = false
        private set

    /**
     * Called once per process start. Returns true when the previous run ended without the
     * normal shutdown path — which is exactly what happens after a crash, including a native
     * one (where no Java stack trace exists at all).
     */
    fun beginSession(context: Context): Boolean {
        // A report from an older build is noise after an update (and makes people think the new
        // version still has the old bug), so the log starts clean whenever the version changes.
        val current = appVersion(context)
        val stored = runCatching { versionFile(context).readText().trim() }.getOrDefault("")
        if (stored != current) {
            clear(context)
            runCatching { versionFile(context).writeText(current) }
        }

        val file = sessionFile(context)
        val unclean = try {
            file.exists()
        } catch (e: Throwable) {
            false
        }
        runCatching { file.writeText(System.currentTimeMillis().toString()) }
        hadPreviousUncleanSession = unclean
        return unclean
    }

    fun endSession(context: Context) {
        runCatching { sessionFile(context).delete() }
    }
    }
