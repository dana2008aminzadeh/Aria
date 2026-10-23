package ir.aria.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ir.aria.downloader.util.Logs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The on-device crash/log recorder that makes field problems debuggable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Logs.clear(context)
    }

    @Test
    fun writesAndReadsLines() {
        Logs.d(context, "test", "first line")
        Logs.d(context, "test", "second line")
        val text = Logs.read(context)
        assertTrue(text.contains("first line"))
        assertTrue(text.contains("second line"))
        assertTrue(text.contains("test"))
    }

    @Test
    fun recordsCrashWithDeviceInfoAndStack() {
        val error = IllegalStateException("boom from test")
        Logs.crash(context, Thread.currentThread(), error)

        val pending = Logs.pendingCrash(context)
        assertNotNull(pending)
        assertTrue(pending!!.contains("IllegalStateException"))
        assertTrue(pending.contains("boom from test"))
        assertTrue(pending.contains("دستگاه"))
        assertTrue(Logs.read(context).contains("CRASH"))
    }

    @Test
    fun acknowledgeRemovesThePendingCrashOnlyOnce() {
        Logs.crash(context, Thread.currentThread(), RuntimeException("once"))
        assertNotNull(Logs.pendingCrash(context))
        Logs.acknowledgeCrash(context)
        assertNull(Logs.pendingCrash(context))
        // the normal log stays available
        Logs.d(context, "test", "still here")
        assertTrue(Logs.read(context).contains("still here"))
    }

    @Test
    fun neverThrowsOnWeirdInput() {
        Logs.e(context, "test", null.toString(), null)
        Logs.d(context, "test", "خطای فارسی 🎵")
        assertTrue(Logs.read(context).contains("🎵"))
    }

    @Test
    fun clearRemovesEntriesButKeepsTheVersionHeader() {
        Logs.d(context, "test", "something")
        Logs.clear(context)
        val text = Logs.read(context)
        assertFalse(text.contains("something"))
        // the header always states which build produced the log
        assertTrue(text.contains("آریا دانلودر"))
    }

    @Test
    fun aVersionChangeWipesTheOldLogSoStaleCrashesAreNotShown() {
        Logs.crash(context, Thread.currentThread(), RuntimeException("old build bug"))
        assertNotNull(Logs.pendingCrash(context))
        // pretend the phone just updated from another build
        File(context.filesDir, "last.version").writeText("0.0.0-old")
        Logs.beginSession(context)
        assertNull("an old crash report must not survive an update", Logs.pendingCrash(context))
        assertFalse(Logs.read(context).contains("old build bug"))
    }
}
