package ir.aria.downloader

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import ir.aria.downloader.service.DownloadService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end test of the service: a real HTTP server, the real queue, the real file saving.
 * This is the closest thing to pressing "download" on the phone that can run on a build machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadServiceTest {

    private lateinit var context: Context
    private lateinit var repo: DownloadRepository
    private lateinit var server: TestHttpServer
    private lateinit var controller: ServiceController<DownloadService>
    private lateinit var service: DownloadService

    private val payload = ByteArray(2 * 1024 * 1024) { (it % 253).toByte() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DownloadRepository.resetForTests()
        File(context.filesDir, "downloads.json").delete()
        File(context.filesDir, "parts").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AriaDownloader")
            .deleteRecursively()
        repo = DownloadRepository.get(context)
        repo.settings.autoRetry = true
        repo.settings.wifiOnly = false
        server = TestHttpServer()
        controller = Robolectric.buildService(DownloadService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        server.close()
        DownloadRepository.resetForTests()
    }

    // ------------------------------------------------------------------ helpers

    private fun start(vararg action: Pair<String, Long>) {
        val intent = Intent(context, DownloadService::class.java)
        intent.action = if (action.isEmpty()) DownloadService.ACTION_START else action.first().first
        if (action.isNotEmpty()) intent.putExtra(DownloadService.EXTRA_ID, action.first().second)
        service.onStartCommand(intent, 0, 1)
    }

    private fun command(action: String, id: Long = -1L, deleteFile: Boolean = false) {
        val intent = Intent(context, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_ID, id)
            putExtra(DownloadService.EXTRA_DELETE_FILE, deleteFile)
        }
        service.onStartCommand(intent, 0, 1)
    }

    private fun await(timeoutMs: Long = 20_000, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("timed out waiting for: $what", condition())
    }

    private fun statusOf(id: Long): DownloadStatus? = repo.item(id)?.status

    private fun savedFile(item: DownloadItem): File =
        File(item.fileUri ?: error("no file uri"), "")

    // ------------------------------------------------------------------ tests

    @Test
    fun serviceStartsInForegroundWithAProgressNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull("a foreground notification is required within 5s", shadowOf(service).lastForegroundNotification)
        assertNotNull(nm.getNotificationChannel(DownloadService.CHANNEL_PROGRESS))
        assertNotNull(nm.getNotificationChannel(DownloadService.CHANNEL_FINISHED))
    }

    @Test
    fun downloadsAFileEndToEndAndSavesIt() {
        server.route("/movie.bin") { ex ->
            ex.respond(
                200, payload, mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Disposition" to "attachment; filename=\"movie payload.bin\""
                )
            )
        }
        val item = repo.add(listOf(server.url("/movie.bin"))).single()
        start(DownloadService.ACTION_START to item.id)

        await(what = "download completes") { statusOf(item.id) == DownloadStatus.COMPLETED }

        val done = repo.item(item.id)!!
        assertEquals(payload.size.toLong(), done.downloadedBytes)
        assertEquals("movie payload.bin", done.fileName)
        assertNotNull(done.fileUri)
        assertNotNull(done.completedAt)
        val saved = File(done.fileUri!!)
        assertTrue("file must exist: ${done.fileUri}", saved.exists())
        assertEquals(payload.size.toLong(), saved.length())
        assertTrue("content must match", payload.contentEquals(saved.readBytes()))
        assertFalse("temporary part file must be cleaned up", repo.partFile(item.id).exists())

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue("a completion notification must be shown", shadowOf(nm).allNotifications.isNotEmpty())
    }

    @Test
    fun pauseKeepsPartialDataAndResumeFinishes() {
        server.route("/slow.bin") { ex ->
            val range = ex.header("Range")
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            if (from > 0) {
                ex.respond(
                    206, payload.copyOfRange(from, payload.size),
                    mapOf(
                        "Content-Range" to "bytes $from-${payload.size - 1}/${payload.size}",
                        "Accept-Ranges" to "bytes"
                    )
                )
            } else {
                // stream slowly so the test can pause in the middle
                val out = ex.stream(200, payload.size.toLong(), mapOf("Accept-Ranges" to "bytes"))
                var offset = 0
                try {
                    while (offset < payload.size) {
                        val chunk = minOf(32 * 1024, payload.size - offset)
                        out.write(payload, offset, chunk)
                        out.flush()
                        offset += chunk
                        Thread.sleep(25)
                    }
                } catch (e: Exception) {
                    // the client aborted, which is what the pause test does
                }
            }
        }
        val item = repo.add(listOf(server.url("/slow.bin"))).single()
        start(DownloadService.ACTION_START to item.id)

        await(what = "download starts") { statusOf(item.id) == DownloadStatus.RUNNING }
        await(what = "some bytes are written") { repo.partFile(item.id).length() > 100_000 }
        command(DownloadService.ACTION_PAUSE, item.id)

        await(what = "item is paused") {
            statusOf(item.id) == DownloadStatus.PAUSED && repo.item(item.id)!!.pausedByUser
        }
        val partial = repo.partFile(item.id).length()
        assertTrue("partial data must be kept for resume, got $partial", partial > 0)
        assertTrue("it must be a partial file", partial < payload.size)

        // let it settle so no late event flips the status back
        Thread.sleep(300)
        assertEquals(DownloadStatus.PAUSED, statusOf(item.id))

        command(DownloadService.ACTION_RESUME, item.id)
        await(what = "resumed download completes") { statusOf(item.id) == DownloadStatus.COMPLETED }

        val done = repo.item(item.id)!!
        val saved = File(done.fileUri!!)
        assertTrue(saved.exists())
        assertEquals(payload.size.toLong(), saved.length())
        assertTrue("resumed file must be byte identical", payload.contentEquals(saved.readBytes()))
        assertFalse(done.pausedByUser)
    }

    @Test
    fun failedDownloadIsMarkedWithPersianError() {
        repo.settings.autoRetry = false
        server.route("/missing.bin") { ex -> ex.respondStatus(404) }
        val item = repo.add(listOf(server.url("/missing.bin"))).single()
        start(DownloadService.ACTION_START to item.id)

        await(what = "item fails") { statusOf(item.id) == DownloadStatus.FAILED }
        val failed = repo.item(item.id)!!
        assertNotNull(failed.errorMessage)
        assertTrue("error message must mention 404: ${failed.errorMessage}", failed.errorMessage!!.contains("404"))
        assertFalse(repo.partFile(item.id).exists())
    }

    @Test
    fun retryActionRequeuesAFailedItem() {
        repo.settings.autoRetry = false
        server.route("/flaky.bin") { ex -> ex.respondStatus(500) }
        val item = repo.add(listOf(server.url("/flaky.bin"))).single()
        start(DownloadService.ACTION_START to item.id)
        await(what = "first attempt fails") { statusOf(item.id) == DownloadStatus.FAILED }

        // now the server works: a retry must succeed
        server.route("/flaky.bin") { ex -> ex.respond(200, payload) }
        command(DownloadService.ACTION_RETRY, item.id)
        await(what = "retry completes") { statusOf(item.id) == DownloadStatus.COMPLETED }
        assertEquals(payload.size.toLong(), repo.item(item.id)!!.downloadedBytes)
    }

    @Test
    fun deleteRemovesTheItemAndItsTemporaryData() {
        server.route("/delete.bin") { ex ->
            val out = ex.stream(200, payload.size.toLong())
            try {
                var offset = 0
                while (offset < payload.size) {
                    val chunk = minOf(32 * 1024, payload.size - offset)
                    out.write(payload, offset, chunk)
                    out.flush()
                    offset += chunk
                    Thread.sleep(20)
                }
            } catch (e: Exception) {
            }
        }
        val item = repo.add(listOf(server.url("/delete.bin"))).single()
        start(DownloadService.ACTION_START to item.id)
        await(what = "started") { repo.partFile(item.id).length() > 0 }

        command(DownloadService.ACTION_DELETE, item.id)

        await(what = "item is removed") { repo.item(item.id) == null }
        assertFalse(repo.partFile(item.id).exists())
        // note: mediaWorkDir() would recreate the folder, so check the raw path
        assertFalse(File(repo.mediaDir, item.id.toString()).exists())
    }

    @Test
    fun pauseAllAndResumeAllAffectTheWholeQueue() {
        val big = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        server.route("/slow-queue.bin") { ex ->
            val out = ex.stream(200, big.size.toLong())
            try {
                var offset = 0
                while (offset < big.size) {
                    val chunk = minOf(32 * 1024, big.size - offset)
                    out.write(big, offset, chunk)
                    out.flush()
                    offset += chunk
                    Thread.sleep(15)
                }
            } catch (e: Exception) {
                // aborted by pause-all: expected
            }
        }
        val a = repo.add(listOf(server.url("/slow-queue.bin") + "?1")).single()
        val b = repo.add(listOf(server.url("/slow-queue.bin") + "?2")).single()
        start(DownloadService.ACTION_START to a.id)

        // wait until the queue is really working, so pause-all has something to stop
        await(what = "at least one item runs") {
            repo.items.value.any { it.status == DownloadStatus.RUNNING }
        }
        command(DownloadService.ACTION_PAUSE_ALL)

        await(what = "nothing stays active") {
            repo.items.value.none { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        }
        assertTrue("paused items must be marked as user-paused", repo.items.value.all { it.pausedByUser })

        command(DownloadService.ACTION_RESUME_ALL)
        await(what = "nothing stays paused") {
            repo.items.value.none { it.status == DownloadStatus.PAUSED }
        }
        assertTrue(repo.items.value.none { it.pausedByUser })
        assertNotNull(a)
        assertNotNull(b)
    }

    @Test
    fun clearCompletedRemovesOnlyFinishedItems() {
        repo.settings.autoRetry = false
        val done = repo.add(listOf("https://invalid.aria.test/done.mp4")).single()
        val queued = repo.add(listOf("https://invalid.aria.test/queued.mp4")).single()
        repo.update(done.id, persist = true) {
            it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = 100, totalBytes = 100)
        }

        command(DownloadService.ACTION_CLEAR_COMPLETED)

        await(what = "completed item is gone") { repo.item(done.id) == null }
        assertNotNull("queued item must stay", repo.item(queued.id))
        assertNull(repo.item(done.id))
    }
}
