package ir.aria.downloader

import ir.aria.downloader.net.HttpDownloader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The native engine is the part users hit most (every direct link, every retry, every resume),
 * so it is exercised against a real socket server: redirects, ranges, chunked bodies and aborts.
 */
class HttpDownloaderTest {

    private lateinit var server: TestHttpServer
    private lateinit var dir: File
    private val downloader = HttpDownloader(connectTimeoutMs = 5_000, readTimeoutMs = 5_000)

    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = TestHttpServer()
        dir = Files.createTempDirectory("aria-http").toFile()
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun target(name: String = "out.bin") = File(dir, name)

    private class Recorder : HttpDownloader.Listener {
        val metadata = ArrayList<HttpDownloader.Metadata>()
        val progress = ArrayList<Triple<Long, Long, Long>>()
        override fun onMetadata(metadata: HttpDownloader.Metadata) {
            this.metadata.add(metadata)
        }

        override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBps: Long, etaSeconds: Long) {
            progress.add(Triple(downloadedBytes, totalBytes, speedBps))
        }
    }

    @Test
    fun downloadsWholeFileAndReportsMetadata() {
        server.route("/file.bin") { ex ->
            ex.respond(
                200, payload, mapOf(
                    "Content-Type" to "application/octet-stream",
                    "ETag" to "\"v1\"",
                    "Accept-Ranges" to "bytes",
                    "Content-Disposition" to "attachment; filename=\"real-name.bin\""
                )
            )
        }
        val recorder = Recorder()
        val file = target()
        val meta = downloader.download(server.url("/file.bin"), file, null, recorder)

        assertTrue(file.exists())
        assertEquals(payload.size.toLong(), file.length())
        assertTrue(payload.contentEquals(file.readBytes()))
        assertEquals(payload.size.toLong(), meta.totalBytes)
        assertTrue(meta.resumable)
        assertEquals("\"v1\"", meta.etag)
        assertEquals("attachment; filename=\"real-name.bin\"", meta.contentDisposition)
        assertEquals("application/octet-stream", meta.contentType)
        assertEquals(1, recorder.metadata.size)
        assertEquals(payload.size.toLong(), recorder.progress.last().first)
    }

    @Test
    fun handlesChunkedResponseWithoutContentLength() {
        server.route("/chunked") { ex ->
            val out = ex.streamChunked(200, mapOf("Content-Type" to "text/plain"))
            out.write(payload, 0, 100_000)
            out.flush()
            out.write(payload, 100_000, 100_000)
            out.close()
        }
        val file = target("chunked.bin")
        val meta = downloader.download(server.url("/chunked"), file, null, Recorder())

        assertEquals(-1L, meta.totalBytes)
        assertEquals(200_000L, file.length())
        assertFalse(meta.resumable)
    }

    @Test
    fun resumesFromExistingBytesWithRangeRequest() {
        val half = payload.size / 2
        server.route("/resume.bin") { ex ->
            val range = ex.header("Range")
            if (range == "bytes=$half-") {
                ex.respond(
                    206,
                    payload.copyOfRange(half, payload.size),
                    mapOf(
                        "Content-Range" to "bytes $half-${payload.size - 1}/${payload.size}",
                        "Accept-Ranges" to "bytes",
                        "ETag" to "\"v1\""
                    )
                )
            } else {
                ex.respond(200, payload, mapOf("Accept-Ranges" to "bytes", "ETag" to "\"v1\""))
            }
        }
        val file = target("resume.bin")
        file.writeBytes(payload.copyOfRange(0, half))

        val meta = downloader.download(server.url("/resume.bin"), file, "\"v1\"", Recorder())

        assertEquals(payload.size.toLong(), file.length())
        assertTrue(payload.contentEquals(file.readBytes()))
        assertEquals(payload.size.toLong(), meta.totalBytes)
        assertTrue(meta.resumable)
    }

    @Test
    fun restartsWhenServerIgnoresRangeHeader() {
        val file = target("ignored.bin")
        file.writeBytes(ByteArray(5000) { 7 })
        server.route("/no-range.bin") { ex -> ex.respond(200, payload) }

        downloader.download(server.url("/no-range.bin"), file, null, Recorder())

        assertEquals(payload.size.toLong(), file.length())
        assertTrue(payload.contentEquals(file.readBytes()))
    }

    @Test
    fun restartsWhenRangeIsNotSatisfiable() {
        server.route("/short.bin") { ex ->
            if (ex.header("Range") != null) ex.respondStatus(416)
            else ex.respond(200, payload)
        }
        val file = target("short.bin")
        file.writeBytes(ByteArray(payload.size + 10))

        downloader.download(server.url("/short.bin"), file, null, Recorder())

        assertEquals(payload.size.toLong(), file.length())
        assertTrue(payload.contentEquals(file.readBytes()))
    }

    @Test
    fun followsRedirectsAndRecordsFinalUrl() {
        server.route("/start") { ex -> ex.respondStatus(302, mapOf("Location" to "/step2")) }
        server.route("/step2") { ex -> ex.respondStatus(302, mapOf("Location" to server.url("/final.bin"))) }
        server.route("/final.bin") { ex -> ex.respond(200, payload) }

        val recorder = Recorder()
        val file = target("redirect.bin")
        val meta = downloader.download(server.url("/start"), file, null, recorder)

        assertTrue(payload.contentEquals(file.readBytes()))
        assertTrue("final url must point at the last hop", meta.finalUrl.endsWith("/final.bin"))
        assertEquals(1, recorder.metadata.size)
    }

    @Test
    fun throwsHttpExceptionWithStatusCode() {
        server.route("/gone") { ex -> ex.respondStatus(404) }
        val file = target("missing.bin")
        try {
            downloader.download(server.url("/gone"), file, null, Recorder())
            fail("expected HttpException")
        } catch (expected: HttpDownloader.HttpException) {
            assertEquals(404, expected.code)
        }
        assertFalse("nothing should be written for an error response", file.exists())
    }

    @Test
    fun survivesServerErrorAndKeepsPartialFile() {
        server.route("/broken") { ex ->
            val out = ex.stream(200, payload.size.toLong())
            out.write(payload, 0, 1000)
            out.flush()
            // drop the connection far too early
            ex.flush()
        }
        val file = target("broken.bin")
        try {
            downloader.download(server.url("/broken"), file, null, Recorder())
            fail("expected IOException for a truncated body")
        } catch (expected: IOException) {
            assertTrue(expected !is HttpDownloader.CancelledException)
        }
        assertTrue("partial bytes stay on disk for a resume", file.length() in 1..payload.size.toLong())
    }

    @Test
    fun abortStopsTheTransferQuickly() {
        val serverWroteEverything = AtomicBoolean(false)
        server.route("/slow") { ex ->
            val out = ex.stream(200, 50L * 1024 * 1024)
            val chunk = ByteArray(32 * 1024)
            try {
                repeat(1600) {
                    out.write(chunk)
                    out.flush()
                    Thread.sleep(5)
                }
                serverWroteEverything.set(true)
            } catch (e: IOException) {
                // client disconnected: expected
            }
        }
        val aborter = Thread {
            Thread.sleep(400)
            downloader.abort()
        }.apply { isDaemon = true; start() }

        val file = target("slow.bin")
        val started = System.currentTimeMillis()
        try {
            downloader.download(server.url("/slow"), file, null, Recorder())
            fail("expected CancelledException")
        } catch (expected: HttpDownloader.CancelledException) {
            // expected
        } finally {
            aborter.join(2000)
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("abort must not wait for the whole file (took ${elapsed}ms)", elapsed < 8000)
        assertFalse("server was still streaming when the client gave up", serverWroteEverything.get())
        assertTrue("some bytes may be kept for resume", file.length() < 50L * 1024 * 1024)
    }

    @Test
    fun progressIsReportedMonotonically() {
        val ticks = ArrayList<Long>()
        server.route("/big") { ex ->
            val out = ex.stream(200, 4L * 1024 * 1024)
            val chunk = ByteArray(64 * 1024)
            repeat(64) {
                out.write(chunk)
                out.flush()
                Thread.sleep(60)
            }
        }
        downloader.download(server.url("/big"), target("big.bin"), null, object : HttpDownloader.Listener {
            override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBps: Long, etaSeconds: Long) {
                ticks.add(downloadedBytes)
            }
        })
        assertTrue("expected several progress events, got ${ticks.size}", ticks.size >= 2)
        assertEquals(ticks.sorted(), ticks)
        assertEquals(4L * 1024 * 1024, ticks.last())
    }

    @Test
    fun contentRangeParsingCoversEdgeCases() {
        assertEquals(0L to 1000L, HttpDownloader.parseContentRange("bytes 0-999/1000"))
        assertEquals(500L to -1L, HttpDownloader.parseContentRange("bytes 500-999/*"))
        assertEquals(7L to 20L, HttpDownloader.parseContentRange("BYTES 7-19/20"))
        assertNull(HttpDownloader.parseContentRange(null))
        assertNull(HttpDownloader.parseContentRange("items 0-3/4"))
    }

    @Test
    fun urlHelpersHandleRelativeAndAbsoluteLocations() {
        assertEquals("https://a.com/x/y.bin", HttpDownloader.resolve("https://a.com/x/z", "y.bin"))
        assertEquals("https://b.com/f", HttpDownloader.resolve("https://a.com/x", "https://b.com/f"))
        assertEquals("https://a.com/y", HttpDownloader.resolve("https://a.com/x/z", "../y"))
        assertEquals("https://a.com/a%20b.mp4", HttpDownloader.normalizeUrl(" https://a.com/a b.mp4 "))
    }

    @Test
    fun listenerExceptionsDoNotLeaveDanglingConnections() {
        server.route("/meta-fail") { ex -> ex.respond(200, payload) }
        val file = target("meta-fail.bin")
        try {
            downloader.download(server.url("/meta-fail"), file, null, object : HttpDownloader.Listener {
                override fun onMetadata(metadata: HttpDownloader.Metadata) = throw IllegalStateException("boom")
            })
            fail("listener exception must surface")
        } catch (expected: IllegalStateException) {
            assertEquals("boom", expected.message)
        }
        // a second download on the same instance must still work
        server.route("/after") { ex -> ex.respond(200, payload) }
        val second = target("after.bin")
        downloader.download(server.url("/after"), second, null, Recorder())
        assertEquals(payload.size.toLong(), second.length())
    }

    @Test
    fun writesIntoNestedMissingDirectories() {
        server.route("/nested") { ex -> ex.respond(200, payload) }
        val file = File(dir, "a/b/c/nested.bin")
        downloader.download(server.url("/nested"), file, null, Recorder())
        assertTrue(file.exists())
        assertEquals(payload.size.toLong(), file.length())
    }

    @Test
    fun emptyBodyProducesEmptyFile() {
        server.route("/empty") { ex -> ex.respond(200, ByteArray(0)) }
        val file = target("empty.bin")
        val meta = downloader.download(server.url("/empty"), file, null, Recorder())
        assertTrue(file.exists())
        assertEquals(0L, file.length())
        assertEquals(0L, meta.totalBytes)
        assertNotNull(file.parentFile)
    }
}
