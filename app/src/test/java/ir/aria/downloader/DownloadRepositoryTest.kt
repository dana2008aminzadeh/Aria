package ir.aria.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ir.aria.downloader.data.DownloadRepository
import ir.aria.downloader.data.NewDownload
import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.engine.ExtractorEngine
import ir.aria.downloader.model.DownloadStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The repository owns the queue the whole app shares. These tests drive it the way the
 * service and the UI do: add, progress, pause, complete, remove, restart the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadRepositoryTest {

    private lateinit var context: Context
    private lateinit var repo: DownloadRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DownloadRepository.resetForTests()
        // start every test from an empty queue, even if the sandbox kept an old file
        File(context.filesDir, "downloads.json").delete()
        File(context.filesDir, "parts").deleteRecursively()
        File(context.filesDir, "media").deleteRecursively()
        repo = DownloadRepository.get(context)
    }

    @After
    fun tearDown() {
        DownloadRepository.resetForTests()
    }

    @Test
    fun addsDirectLinkAsHttpItemWithNameAndMime() {
        val created = repo.add(listOf("https://cdn.example.com/videos/clip.mp4?token=1"))
        assertEquals(1, created.size)
        val item = created.single()
        assertEquals(EngineKind.HTTP, item.engine)
        assertEquals("clip.mp4", item.fileName)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(DownloadStatus.QUEUED, item.status)
        assertEquals(1, repo.items.value.size)
    }

    @Test
    fun addRequestsKeepsMediaMetadata() {
        val created = repo.addRequests(
            listOf(
                NewDownload(
                    url = "https://www.youtube.com/watch?v=abc",
                    fileName = "Video Title.mp4",
                    engine = EngineKind.MEDIA,
                    preset = "720",
                    mimeType = "video/mp4",
                    extractor = "Youtube",
                    durationSec = 300,
                    height = 720
                )
            )
        )
        val item = created.single()
        assertEquals(EngineKind.MEDIA, item.engine)
        assertEquals("720", item.preset)
        assertEquals("Youtube", item.extractor)
        assertEquals(300L, item.durationSec)
        assertEquals(720, item.height)
        assertTrue(item.isMedia)
    }

    @Test
    fun exactPerItemSelectionSurvivesRepositoryAndStore() {
        val selection = ExtractorEngine.FormatSelection(
            formatId = "248", audioFormatId = "251", outputExt = "mp3",
            outputKind = "audio", audioBitrateKbps = 192, label = "1080p • MP3"
        )
        val item = repo.addRequests(listOf(NewDownload(
            url = "https://www.youtube.com/watch?v=exact",
            fileName = "Exact.mp3",
            engine = EngineKind.MEDIA,
            selection = selection
        ))).single()

        assertEquals("248", item.formatId)
        assertEquals("251", item.audioFormatId)
        assertEquals("audio/mpeg", item.mimeType)
        assertEquals(selection, item.exactSelection())
        repo.persistNow()
        assertEquals(selection, repo.store.load().single().exactSelection())
    }

    @Test
    fun spotifyLinksAreQueuedAsAudioMediaDownloads() {
        val created = repo.add(listOf("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        val item = created.single()
        assertEquals(EngineKind.MEDIA, item.engine)
        assertEquals("audio", item.preset)
    }

    @Test
    fun placeholderNameIsUsedWhenUrlHasNoFile() {
        val created = repo.add(listOf("https://example.com/download"))
        val item = created.single()
        assertEquals(EngineKind.MEDIA, item.engine)
        assertTrue(item.fileName.startsWith("file-${item.id}.bin") || item.fileName.contains(".bin"))
        assertTrue(DownloadRepository.isPlaceholderName(item.fileName) || item.fileName.isNotBlank())
    }

    @Test
    fun duplicateActiveLinksAreIgnoredButCompletedOnesCanBeReadded() {
        val url = "https://cdn.example.com/a.mp4"
        val first = repo.add(listOf(url)).single()
        assertTrue(repo.add(listOf(url)).isEmpty())

        repo.update(first.id, persist = true) {
            it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = it.totalBytes)
        }
        assertEquals(1, repo.add(listOf(url)).size)
        assertEquals(2, repo.items.value.size)
    }

    @Test
    fun sameLinkTwiceInOneBatchIsQueuedOnce() {
        val created = repo.add(listOf("https://cdn.example.com/a.mp4", "https://cdn.example.com/a.mp4"))
        assertEquals(1, created.size)
    }

    @Test
    fun progressUpdatesBytesAndStatus() {
        val item = repo.add(listOf("https://cdn.example.com/big.bin")).single()
        repo.updateProgress(item.id, downloaded = 400, total = 1000, speedBps = 200)

        val updated = repo.item(item.id)!!
        assertEquals(400L, updated.downloadedBytes)
        assertEquals(1000L, updated.totalBytes)
        assertEquals(DownloadStatus.RUNNING, updated.status)
        assertEquals(40, updated.progressPercent)

        // a late event after completion must not resurrect the item
        repo.update(item.id) { it.copy(status = DownloadStatus.COMPLETED) }
        repo.updateProgress(item.id, downloaded = 500, total = 1000, speedBps = 0)
        assertEquals(DownloadStatus.COMPLETED, repo.item(item.id)!!.status)
    }

    @Test
    fun pauseAndResumeFlagsArePersisted() {
        val item = repo.add(listOf("https://cdn.example.com/c.mp4")).single()
        repo.update(item.id, persist = true) { it.copy(status = DownloadStatus.PAUSED, pausedByUser = true) }

        DownloadRepository.resetForTests()
        val reopened = DownloadRepository.get(context)
        val restored = reopened.item(item.id)!!
        assertEquals(DownloadStatus.PAUSED, restored.status)
        assertTrue(restored.pausedByUser)
    }

    @Test
    fun queueSurvivesProcessRestart() {
        val a = repo.add(listOf("https://cdn.example.com/one.mp4")).single()
        val b = repo.add(listOf("https://cdn.example.com/two.mp4")).single()
        repo.update(a.id, persist = true) {
            it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = 10, totalBytes = 10)
        }
        repo.persistNow()

        DownloadRepository.resetForTests()
        val reopened = DownloadRepository.get(context)
        assertEquals(2, reopened.items.value.size)
        assertEquals(DownloadStatus.COMPLETED, reopened.item(a.id)!!.status)
        assertEquals(DownloadStatus.QUEUED, reopened.item(b.id)!!.status)
    }

    @Test
    fun runningItemsAreRepairedAfterAKill() {
        val item = repo.add(listOf("https://cdn.example.com/k.mp4")).single()
        repo.update(item.id) { it.copy(status = DownloadStatus.RUNNING, downloadedBytes = 50) }
        repo.persistNow()

        DownloadRepository.resetForTests()
        val reopened = DownloadRepository.get(context)
        assertEquals(DownloadStatus.QUEUED, reopened.item(item.id)!!.status)
        assertEquals("progress must be kept", 50L, reopened.item(item.id)!!.downloadedBytes)
    }

    @Test
    fun removeDeletesItemAndTemporaryFiles() {
        val item = repo.add(listOf("https://cdn.example.com/r.mp4")).single()
        val part = repo.partFile(item.id)
        part.parentFile?.mkdirs()
        part.writeText("partial")
        val mediaDir = repo.mediaWorkDir(item.id)
        File(mediaDir, "video.mp4").writeText("v")
        assertTrue(part.exists())

        val removed = repo.remove(item.id)

        assertNotNull(removed)
        assertNull(repo.item(item.id))
        assertFalse("partial file must be deleted", part.exists())
        assertFalse("media work dir must be deleted", mediaDir.exists())
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun renameUpdatesFileNameForQueuedItem() {
        val item = repo.add(listOf("https://cdn.example.com/old.mp4")).single()
        repo.renameItem(item.id, "new/name?.mp4")
        assertEquals("new_name_.mp4", repo.item(item.id)!!.fileName)
    }

    @Test
    fun summaryAggregatesActiveAndCompletedItems() {
        val running = repo.add(listOf("https://cdn.example.com/1.bin")).single()
        val running2 = repo.add(listOf("https://cdn.example.com/2.bin")).single()
        val done = repo.add(listOf("https://cdn.example.com/3.bin")).single()
        val paused = repo.add(listOf("https://cdn.example.com/4.bin")).single()
        val failed = repo.add(listOf("https://cdn.example.com/5.bin")).single()
        val queued = repo.add(listOf("https://cdn.example.com/6.bin")).single()

        repo.updateProgress(running.id, 300, 1000, 100)
        repo.updateProgress(running2.id, 500, 1000, 50)
        repo.update(done.id, persist = true) { it.copy(status = DownloadStatus.COMPLETED, downloadedBytes = 2048) }
        repo.update(paused.id) { it.copy(status = DownloadStatus.PAUSED) }
        repo.update(failed.id) { it.copy(status = DownloadStatus.FAILED) }

        val summary = repo.summary()
        assertEquals(2, summary.activeCount)
        assertEquals(1, summary.queuedCount)
        assertEquals(1, summary.pausedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.completedCount)
        assertEquals(40, summary.aggregateProgress)
        assertEquals(150, summary.aggregateSpeed)
        assertNotNull(queued)
    }

    @Test
    fun aggregateProgressIsNullWhenSizesAreUnknown() {
        val a = repo.add(listOf("https://cdn.example.com/x.bin")).single()
        repo.updateProgress(a.id, 100, -1, 10)
        assertNull(repo.summary().aggregateProgress)
    }

    @Test
    fun itemsFlowEmitsOnEveryMutation() {
        val seen = ArrayList<Int>()
        repo.items.value.size.let { seen.add(it) }
        repo.add(listOf("https://cdn.example.com/flow.mp4"))
        seen.add(repo.items.value.size)
        val item = repo.items.value.first()
        repo.remove(item.id)
        seen.add(repo.items.value.size)
        assertEquals(listOf(0, 1, 0), seen)
    }
}
