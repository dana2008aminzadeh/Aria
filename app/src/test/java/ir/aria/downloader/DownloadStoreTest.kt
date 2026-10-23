package ir.aria.downloader

import ir.aria.downloader.data.DownloadStore
import ir.aria.downloader.engine.EngineKind
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadStoreTest {

    private lateinit var dir: File
    private lateinit var queueFile: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("aria-store").toFile()
        queueFile = File(dir, "downloads.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(id: Long, status: DownloadStatus = DownloadStatus.QUEUED, name: String = "file$id.mp4") =
        DownloadItem(
            id = id,
            url = "https://example.com/$name",
            fileName = name,
            mimeType = "video/mp4",
            totalBytes = 1000,
            downloadedBytes = if (status == DownloadStatus.COMPLETED) 1000 else 250,
            status = status,
            completedAt = if (status == DownloadStatus.COMPLETED) 123456789L else null
        )

    @Test
    fun persistsQueueAcrossInstances() {
        val store = DownloadStore(queueFile)
        store.load()
        val id1 = store.newId()
        val id2 = store.newId()
        store.upsert(item(id1, DownloadStatus.COMPLETED))
        store.upsert(item(id2, DownloadStatus.PAUSED, "second.mp4"))
        assertTrue(store.save())
        assertTrue(queueFile.exists())

        val restored = DownloadStore(queueFile).load()
        assertEquals(2, restored.size)
        val first = restored.first { it.id == id1 }
        assertEquals(DownloadStatus.COMPLETED, first.status)
        assertEquals(1000L, first.downloadedBytes)
        assertEquals(123456789L, first.completedAt)
        assertEquals(DownloadStatus.PAUSED, restored.first { it.id == id2 }.status)
    }

    @Test
    fun roundTripsAllOptionalFields() {
        val original = DownloadItem(
            id = 5,
            url = "https://cdn/a.mp4?sig=1&x=2",
            fileName = "فیلم اصلی.mp4",
            mimeType = "video/mp4",
            totalBytes = 999,
            downloadedBytes = 111,
            status = DownloadStatus.FAILED,
            etag = "\"abc123\"",
            lastModified = "Wed, 21 Oct 2026 07:28:00 GMT",
            supportsResume = false,
            attempts = 2,
            pausedByUser = true,
            errorMessage = "خطای شبکه",
            fileUri = "content://downloads/1"
        )
        val store = DownloadStore(queueFile)
        store.load()
        store.upsert(original)
        store.save()

        assertEquals(original, DownloadStore(queueFile).load().single())
    }

    @Test
    fun roundTripsMediaEngineFields() {
        val media = DownloadItem(
            id = 9,
            url = "https://www.youtube.com/watch?v=abc",
            fileName = "My Video.mp4",
            mimeType = "video/mp4",
            engine = EngineKind.MEDIA,
            preset = "720",
            formatId = "298",
            title = "My Video",
            extractor = "Youtube",
            warning = "ادغام انجام شد",
            height = 720,
            durationSec = 635
        )
        val store = DownloadStore(queueFile)
        store.load()
        store.upsert(media)
        store.save()

        val restored = DownloadStore(queueFile).load().single()
        assertEquals(EngineKind.MEDIA, restored.engine)
        assertEquals("720", restored.preset)
        assertEquals("298", restored.formatId)
        assertEquals("Youtube", restored.extractor)
        assertEquals(720, restored.height)
        assertEquals(635L, restored.durationSec)
        assertEquals("ادغام انجام شد", restored.warning)
        assertTrue(restored.isMedia)
    }

    @Test
    fun oldQueueWithoutEngineFieldsStillLoads() {
        // a queue file written by version 1.0 must stay usable after the update
        queueFile.writeText(
            """{"version":1,"nextId":3,"items":[
               {"id":1,"url":"https://example.com/a.mp4","fileName":"a.mp4","status":"COMPLETED","downloadedBytes":100,"totalBytes":100},
               {"id":2,"url":"https://example.com/b.zip","fileName":"b.zip","status":"PAUSED","downloadedBytes":10}
            ]}"""
        )
        val store = DownloadStore(queueFile)
        val items = store.load()
        assertEquals(2, items.size)
        assertTrue(items.all { it.engine == EngineKind.HTTP })
        assertEquals("best", items.first().preset)
        assertEquals(3L, store.newId())
    }

    @Test
    fun nextIdNeverCollidesWithRestoredItems() {
        val store = DownloadStore(queueFile)
        store.load()
        store.upsert(item(store.newId()))
        store.upsert(item(store.newId()))
        store.save()

        val restored = DownloadStore(queueFile)
        restored.load()
        val existingIds = restored.all().map { it.id }.toSet()
        val newId = restored.newId()
        assertTrue(newId > existingIds.max())
        assertTrue(newId !in existingIds)
    }

    @Test
    fun repairsInterruptedRunningItems() {
        val store = DownloadStore(queueFile)
        store.load()
        val runningId = store.newId()
        store.upsert(item(runningId, DownloadStatus.RUNNING))
        store.save()

        val restored = DownloadStore(queueFile)
        restored.load()
        val repaired = restored.repairInterrupted()
        assertEquals(1, repaired.size)
        assertEquals(DownloadStatus.QUEUED, restored.get(runningId)?.status)
    }

    @Test
    fun survivesCorruptQueueFile() {
        queueFile.writeText("{ this is not json")
        val store = DownloadStore(queueFile)
        val items = store.load()
        assertTrue(items.isEmpty())
        assertTrue(File(dir, "downloads.json.corrupt").exists())
        store.upsert(item(store.newId()))
        assertTrue(store.save())
        assertEquals(1, DownloadStore(queueFile).load().size)
    }

    @Test
    fun removeDeletesEntry() {
        val store = DownloadStore(queueFile)
        store.load()
        val id = store.newId()
        store.upsert(item(id))
        assertNotNull(store.get(id))
        assertNotNull(store.remove(id))
        assertNull(store.get(id))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun statsGroupByStatus() {
        val store = DownloadStore(queueFile)
        store.load()
        store.upsert(item(store.newId(), DownloadStatus.COMPLETED))
        store.upsert(item(store.newId(), DownloadStatus.COMPLETED))
        store.upsert(item(store.newId(), DownloadStatus.RUNNING))
        val stats = store.stats()
        assertEquals(2, stats[DownloadStatus.COMPLETED])
        assertEquals(1, stats[DownloadStatus.RUNNING])
    }
}
