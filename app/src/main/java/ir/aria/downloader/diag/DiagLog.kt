package ir.aria.downloader.diag

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import ir.aria.downloader.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Full-session diagnostic logger, compiled in always but only *persisting* in the `diag`
 * build type (`BuildConfig.DIAGNOSTICS`). In debug/release builds every call degrades to a
 * plain logcat write with no file IO, so production behavior is unchanged.
 *
 * In the diag build it records everything (app/activity lifecycle, user actions, queue state
 * transitions, engine calls, HTTP traffic, FFmpeg runs, errors and crashes) into
 * `filesDir/diagnostics/alldown-diag.log` with size rotation, ready to be shared from
 * Settings -> «لاگ تشخیصی».
 *
 * Privacy rule: download links are logged on purpose (that is what the tester is debugging),
 * but secrets never are — proxy/URL credentials, signed-url tokens, Authorization/Cookie
 * headers and the cookies.txt content are always redacted before anything hits the file.
 */
object DiagLog {

    /** True only in the `diag` build type. File persistence is gated on this flag alone. */
    val enabled: Boolean = BuildConfig.DIAGNOSTICS

    const val DIR_NAME = "diagnostics"
    const val FILE_NAME = "alldown-diag.log"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_RING_LINES = 3000
    private const val ROTATE_CHECK_EVERY = 200

    private val lock = Any()
    private val ring = ArrayDeque<String>()

    @Volatile private var logDir: File? = null
    @Volatile private var logFile: File? = null
    private var stream: FileOutputStream? = null
    private var writesSinceRotateCheck = 0
    private var initialized = false
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    // ------------------------------------------------------------------ lifecycle

    /**
     * Opens the log file and writes the session header. Safe to call in every build type and
     * any number of times; outside `diag` builds (and in plain JVM unit tests) it is a no-op.
     */
    fun init(context: Context) {
        // Idempotent: a second call in the same process must not start a second session header.
        if (!enabled) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            runCatching {
                val appContext = context.applicationContext
                val dir = File(appContext.filesDir, DIR_NAME).apply { mkdirs() }
                logDir = dir
                val file = File(dir, FILE_NAME)
                logFile = file
                if (file.exists() && file.length() > MAX_FILE_BYTES) rotateLocked()
                openStreamLocked()
                writeRawLocked(sessionHeaderLocked(appContext))
                // Anything logged before init (very early App code) is flushed after the header.
                val pending = ring.toList()
                for (line in pending) writeRawLocked(line)
                val crashes = dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.name }.orEmpty()
                if (crashes.isNotEmpty()) {
                    writeRawLocked(formatLineLocked("W", "Diag", "previous crash report(s) on disk: " +
                        crashes.joinToString(", ") { it.name }))
                }
            }
        }
        i("Diag", "diagnostic logging started (file=${logFilePath() ?: "unavailable"})")
    }

    /** Records uncaught crashes to the log (and a separate crash file) before the process dies. */
    fun installCrashHandler() {
        if (!enabled) return
        if (previousCrashHandler != null) return
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                e("Crash", "UNCAUGHT exception on thread '${thread.name}'", throwable)
                val dir = logDir
                if (dir != null) {
                    val name = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
                    val report = buildString {
                        append("AllDown crash report\n")
                        append("time: ").append(timestamp()).append('\n')
                        append("thread: ").append(thread.name).append('\n')
                        append("app: ").append(BuildConfig.VERSION_NAME)
                            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                        append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                            .append(" Android ").append(Build.VERSION.RELEASE)
                            .append(" SDK ").append(Build.VERSION.SDK_INT).append('\n')
                        append(Log.getStackTraceString(throwable))
                    }
                    File(dir, name).writeText(report)
                }
            }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
    }

    // ------------------------------------------------------------------ public logging API

    fun v(tag: String, message: String) = write("V", Log.VERBOSE, tag, message, null)
    fun d(tag: String, message: String) = write("D", Log.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write("I", Log.INFO, tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) =
        write("W", Log.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) =
        write("E", Log.ERROR, tag, message, throwable)

    /** Convenience for progress streams: logs only when the integer percent crosses a 10% step. */
    class ProgressMilestones(private val tag: String, private val label: String) {
        private var lastStep = -1

        @Synchronized
        fun onProgress(downloaded: Long, total: Long, speedBps: Long) {
            if (!DiagLog.enabled) return
            if (total <= 0) return
            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            val step = percent / 10
            if (step == lastStep && percent != 100) return
            if (percent == 100 && lastStep == 10) return
            lastStep = if (percent == 100) 10 else step
            DiagLog.d(tag, "$label progress $percent% ($downloaded/$total bytes, speed=${speedBps}B/s)")
        }
    }

    // ------------------------------------------------------------------ file access for the viewer

    fun logFilePath(): String? = logFile?.absolutePath

    fun logFileSize(): Long = synchronized(lock) {
        var total = 0L
        for (suffix in listOf(".2", ".1", "")) {
            val f = rotatedFile(suffix)
            if (f != null && f.exists()) total += f.length()
        }
        total
    }

    /** Last [maxChars] of the whole (rotated + current) log, for the in-app viewer / clipboard. */
    fun tailText(maxChars: Int = 150_000): String {
        if (!enabled) return ""
        val parts = ArrayList<String>()
        synchronized(lock) {
            runCatching { stream?.flush() }
            for (suffix in listOf(".2", ".1", "")) {
                val f = rotatedFile(suffix) ?: continue
                if (f.exists()) runCatching { parts.add(f.readText()) }
            }
        }
        val text = if (parts.isEmpty()) ringSnapshot() else parts.joinToString("\n")
        return if (text.length > maxChars) {
            "…(ابتدای لاگ بریده شد؛ فایل کامل را اشتراک‌گذاری کنید)…\n" +
                text.substring(text.length - maxChars)
        } else text
    }

    fun ringSnapshot(): String = synchronized(lock) { ring.joinToString("\n") }

    /**
     * Builds one shareable `.log` in cacheDir: session summary of this moment + rotated logs +
     * current log + every crash report. This single file is what the tester should send back.
     */
    fun buildExportFile(context: Context): File {
        val dir = File(context.cacheDir, "diag-share").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val export = File(dir, "alldown-diag-$stamp.log")
        i("Diag", "export requested -> ${export.name}")
        synchronized(lock) { runCatching { stream?.flush() } }
        export.outputStream().use { out ->
            out.write(("AllDown diagnostic export " + timestamp() + "\n").toByteArray())
            out.write(("app " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")" +
                " device " + Build.MANUFACTURER + " " + Build.MODEL +
                " Android " + Build.VERSION.RELEASE + " SDK " + Build.VERSION.SDK_INT + "\n\n").toByteArray())
            for (suffix in listOf(".2", ".1", "")) {
                val f = rotatedFile(suffix) ?: continue
                if (!f.exists()) continue
                out.write(("===== ${f.name} =====\n").toByteArray())
                f.inputStream().use { it.copyTo(out) }
                out.write("\n".toByteArray())
            }
            val crashes = logDir?.listFiles { file -> file.name.startsWith("crash-") }
                ?.sortedBy { it.name }.orEmpty()
            for (crash in crashes) {
                out.write(("===== ${crash.name} =====\n").toByteArray())
                crash.inputStream().use { it.copyTo(out) }
                out.write("\n".toByteArray())
            }
        }
        return export
    }

    /** Deletes every diagnostic file and reopens with a fresh header. */
    fun clear(context: Context) {
        if (!enabled) return
        synchronized(lock) {
            runCatching { stream?.close() }
            stream = null
            ring.clear()
            for (suffix in listOf(".2", ".1", "")) rotatedFile(suffix)?.delete()
            logDir?.listFiles { f -> f.name.startsWith("crash-") }?.forEach { it.delete() }
            openStreamLocked()
            writeRawLocked(formatLineLocked("I", "Diag", "logs cleared by user"))
            runCatching { writeRawLocked(sessionHeaderLocked(context.applicationContext)) }
        }
    }

    // ------------------------------------------------------------------ redaction (pure, unit-tested)

    // A bare "key" substring would also hide harmless content ids such as Pornhub's
    // "viewkey", so key-like names are matched by the exact forms in isSensitiveKey.
    private val SENSITIVE_KEY_PARTS = listOf(
        "token", "sig", "sign", "auth", "secret", "session", "password",
        "passwd", "pwd", "cookie", "credential", "policy", "hmac", "bearer"
    )

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        if (lower == "key" || lower.contains("apikey") || lower.contains("api_key")) return true
        if (lower.endsWith("_key") || lower.endsWith("-key")) return true
        return SENSITIVE_KEY_PARTS.any { lower.contains(it) }
    }

    private val USERINFO = Regex("""://[^/@\s]+@""")
    private val QUERY_PARAM = Regex("""([?&][^=&#\s]+)=([^&#\s]*)""")
    private val BARE_ASSIGN = Regex("""\b([A-Za-z][A-Za-z0-9_\-]{1,40})=([^\s&;'"]+)""")
    private val HEADER_SECRET = Regex(
        """(?i)\b(authorization|cookie|set-cookie|x-api-key)\s*[:=]\s*(?:Bearer\s+)?[^\s;]+"""
    )

    /** Masks credentials and signed-url tokens in a single URL; host/path/ids stay readable. */
    fun redactUrl(url: String?): String {
        if (url.isNullOrEmpty()) return url.orEmpty()
        var result = USERINFO.replace(url) { "://***@" }
        result = QUERY_PARAM.replace(result) { match ->
            val keyWithMark = match.groupValues[1]
            val key = keyWithMark.substring(1)
            if (isSensitiveKey(key)) "$keyWithMark=***" else match.value
        }
        return result
    }

    /** Redacts every URL and secret header inside free-form text (yt-dlp/FFmpeg output etc). */
    fun redactText(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        var result = HEADER_SECRET.replace(text) { match -> match.groupValues[1] + ": ***" }
        result = redactUrl(result)
        // Secrets also appear mid-sentence in error texts ("... token=abc ..."), not only
        // after a "?" in a url.
        result = BARE_ASSIGN.replace(result) { match ->
            val key = match.groupValues[1]
            if (isSensitiveKey(key)) "$key=***" else match.value
        }
        return result
    }

    /** `http://user:pass@127.0.0.1:8080` -> `http://***@127.0.0.1:8080` so the endpoint stays debuggable. */
    fun redactProxy(proxy: String?): String {
        val value = proxy?.trim().orEmpty()
        if (value.isEmpty()) return "(none)"
        return USERINFO.replace(value) { "://***@" }
    }

    // ------------------------------------------------------------------ internals

    private fun write(level: String, logcatPriority: Int, tag: String, message: String, throwable: Throwable?) {
        val safeMessage = redactText(message)
        // Logcat always mirrors production behavior; file persistence is diag-only.
        runCatching {
            when (logcatPriority) {
                Log.VERBOSE -> Log.v(tag, safeMessage)
                Log.DEBUG -> Log.d(tag, safeMessage)
                Log.INFO -> Log.i(tag, safeMessage)
                Log.WARN -> if (throwable != null) Log.w(tag, safeMessage, throwable) else Log.w(tag, safeMessage)
                else -> if (throwable != null) Log.e(tag, safeMessage, throwable) else Log.e(tag, safeMessage)
            }
        }
        if (!enabled) return
        try {
            var line = synchronized(lock) { formatLineLocked(level, tag, safeMessage) }
            if (throwable != null) {
                line += "\n" + Log.getStackTraceString(throwable).trimEnd()
            }
            synchronized(lock) {
                ring.addLast(line)
                while (ring.size > MAX_RING_LINES) ring.removeFirst()
                if (initialized && stream != null) {
                    writeRawLocked(line)
                    if (++writesSinceRotateCheck >= ROTATE_CHECK_EVERY) {
                        writesSinceRotateCheck = 0
                        val f = logFile
                        if (f != null && f.length() > MAX_FILE_BYTES) rotateLocked()
                    }
                }
            }
        } catch (ignored: Throwable) {
            // Diagnostics must never crash the app they are supposed to debug.
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun formatLineLocked(level: String, tag: String, message: String): String {
        val thread = Thread.currentThread().name.take(28)
        val singleLine = message.replace("\r\n", "\n").replace('\n', ' ')
        return timestamp() + " " + level + "/" + tag + " [" + thread + "]: " + singleLine
    }

    private fun sessionHeaderLocked(context: Context): String {
        val free = runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrDefault(-1L)
        val cookies = File(context.filesDir, "cookies.txt")
        val settingsLine = runCatching {
            val s = ir.aria.downloader.data.Settings(context)
            "concurrency=" + s.concurrency +
                " fragmentConcurrency=" + s.fragmentConcurrency +
                " wifiOnly=" + s.wifiOnly +
                " autoRetry=" + s.autoRetry +
                " notifications=" + s.notificationsEnabled +
                " preset=" + s.defaultPreset +
                " proxy=" + redactProxy(s.proxyUrl) +
                " customUA=" + s.mediaUserAgentOverride.isNotEmpty()
        }.getOrDefault("(settings unavailable)")
        return buildString {
            append("\n===== AllDown DIAG SESSION ").append(timestamp()).append(" =====\n")
            append("app: ").append(BuildConfig.VERSION_NAME)
                .append(" (code ").append(BuildConfig.VERSION_CODE).append(")")
                .append(" pkg=").append(context.packageName)
                .append(" buildType=").append(BuildConfig.BUILD_TYPE)
                .append(" diagnostics=").append(BuildConfig.DIAGNOSTICS).append('\n')
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | Android ").append(Build.VERSION.RELEASE)
                .append(" SDK ").append(Build.VERSION.SDK_INT)
                .append(" | ABI ").append(Build.SUPPORTED_ABIS.joinToString(","))
                .append(" | locale=").append(Locale.getDefault())
                .append(" | tz=").append(java.util.TimeZone.getDefault().id).append('\n')
            append("storage: freeBytes=").append(free)
                .append(" filesDir=").append(context.filesDir.absolutePath)
                .append(" ckPresent=").append(cookies.exists() && cookies.length() > 0)
                .append(" ckBytes=").append(if (cookies.exists()) cookies.length() else 0).append('\n')
            append("settings: ").append(settingsLine).append('\n')
            append("=============================================================")
        }
    }

    private fun rotatedFile(suffix: String): File? {
        val dir = logDir ?: return null
        return File(dir, FILE_NAME + suffix)
    }

    private fun openStreamLocked() {
        val file = logFile ?: return
        runCatching { stream?.close() }
        stream = runCatching { FileOutputStream(file, true) }.getOrNull()
    }

    private fun writeRawLocked(line: String) {
        val out = stream ?: return
        runCatching {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun rotateLocked() {
        runCatching { stream?.close() }
        stream = null
        val current = rotatedFile("") ?: return
        rotatedFile(".2")?.delete()
        rotatedFile(".1")?.let { if (it.exists()) it.renameTo(rotatedFile(".2")!!) }
        if (current.exists()) current.renameTo(rotatedFile(".1")!!)
        openStreamLocked()
        writeRawLocked(formatLineLocked("I", "Diag", "log rotated (kept as .1/.2)"))
    }
}
