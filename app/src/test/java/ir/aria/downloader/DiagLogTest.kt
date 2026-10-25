package ir.aria.downloader

import ir.aria.downloader.diag.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diag log is meant to be sent back by the tester, so it must be safe to share:
 * links stay readable (they are the bug report), secrets never survive.
 */
class DiagLogTest {

    @Test
    fun urlTokensAreMasked_butContentIdsStay() {
        assertEquals(
            "https://cdn.example.com/v.mp4?token=***&v=abc",
            DiagLog.redactUrl("https://cdn.example.com/v.mp4?token=sekrit&v=abc")
        )
        // Pornhub's viewkey is a content id, not a secret, and is needed to reproduce a bug.
        assertEquals(
            "https://www.pornhub.com/view_video.php?viewkey=ph123",
            DiagLog.redactUrl("https://www.pornhub.com/view_video.php?viewkey=ph123")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42",
            DiagLog.redactUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42")
        )
    }

    @Test
    fun signedCdnParamsAndUserinfoAreMasked() {
        val input = "https://user:pass@example.com/a?X-Amz-Signature=deadbeef&x=1"
        val out = DiagLog.redactUrl(input)
        assertFalse(out.contains("user"))
        assertFalse(out.contains(":pass"))
        assertEquals("https://***@example.com/a?X-Amz-Signature=***&x=1", out)
    }

    @Test
    fun apiKeyFormsAreMasked() {
        assertTrue(DiagLog.redactUrl("https://x.test/?api_key=zzz").contains("api_key=***"))
        assertTrue(DiagLog.redactUrl("https://x.test/?apikey=zzz").contains("apikey=***"))
        assertTrue(DiagLog.redactUrl("https://x.test/?key=zzz").contains("key=***"))
        assertTrue(DiagLog.redactUrl("https://x.test/?download_key=zzz").contains("download_key=***"))
    }

    @Test
    fun freeTextSecretsAreMasked() {
        val out = DiagLog.redactText(
            "ERROR Authorization: Bearer abc123 token=leakme " +
                "https://cdn.test/x?sig=qwerty sessionid=zz download failed"
        )
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("leakme"))
        assertFalse(out.contains("qwerty"))
        assertFalse(out.contains("sessionid=zz"))
        assertTrue(out.contains("download failed"))
    }

    @Test
    fun proxyKeepsEndpointHidesPassword() {
        assertEquals("http://***@127.0.0.1:8080", DiagLog.redactProxy("http://dana:secret@127.0.0.1:8080"))
        assertEquals("socks5://127.0.0.1:1080", DiagLog.redactProxy("socks5://127.0.0.1:1080"))
        assertEquals("(none)", DiagLog.redactProxy(""))
        assertEquals("(none)", DiagLog.redactProxy(null))
    }

    @Test
    fun plainLogCallsAreSafeWithoutInitOrDiagBuild() {
        // Nobody called DiagLog.init on the JVM and DIAGNOSTICS is false in this build:
        // every API used all over the app must stay a harmless no-op here.
        assertFalse(DiagLog.enabled)
        DiagLog.i("Test", "hello token=should-not-crash")
        DiagLog.e("Test", "with error", IllegalStateException("boom"))
        DiagLog.ProgressMilestones("Test", "x").onProgress(50, 100, 10)
        assertEquals("", DiagLog.tailText())
        assertEquals(0L, DiagLog.logFileSize())
    }
}
