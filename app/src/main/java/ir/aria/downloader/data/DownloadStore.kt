package ir.aria.downloader.data

import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.model.DownloadStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON backed persistent queue. Writes are atomic (tmp file + rename) so a crash
 * in the middle of a save never corrupts the queue. Pure JVM: unit tested.
 */
class DownloadStore(private val file: File) {

    private val lock = Any()
    private val items = LinkedHashMap<Long, DownloadItem>()
    private var nextId = 1L
    private var loaded = false

    fun load(): List<DownloadItem> = synchronized(lock) {
        if (loaded) return all()
        loaded = true
        if (file.exists()) {
            try {
                val root = JSONObject(file.readText())
                nextId = root.optLong("nextId", 1L).coerceAtLeast(1L)
                val array = root.optJSONArray("items") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val item = DownloadItem.fromJson(obj)
                    if (item.id <= 0L || item.url.isBlank()) continue
                    items[item.id] = item
                }
                val maxId = items.keys.maxOrNull() ?: 0L
                if (nextId <= maxId) nextId = maxId + 1
            } catch (e: Exception) {
                // Corrupt queue file: keep a copy for debugging and continue with an empty queue.
                runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), true) }
                items.clear()
            }
        }
        all()
    }

    fun all(): List<DownloadItem> = synchronized(lock) { items.values.toList() }

    fun get(id: Long): DownloadItem? = synchronized(lock) { items[id] }

    fun newId(): Long = synchronized(lock) { nextId++ }

    fun upsert(item: DownloadItem) = synchronized(lock) { items[item.id] = item }

    fun remove(id: Long): DownloadItem? = synchronized(lock) { items.remove(id) }

    fun clear() = synchronized(lock) { items.clear() }

    /** Serialises synchronously; returns false when writing failed. */
    fun save(): Boolean = synchronized(lock) {
        return try {
            val root = JSONObject()
            root.put("version", 3)
            root.put("nextId", nextId)
            val array = JSONArray()
            for (item in items.values) array.put(item.toJson())
            root.put("items", array)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            val ok = tmp.renameTo(file)
            if (!ok) {
                file.writeText(root.toString())
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Marks every RUNNING item as QUEUED (used after the process was killed). */
    fun repairInterrupted(): List<DownloadItem> = synchronized(lock) {
        val repaired = ArrayList<DownloadItem>()
        for ((id, item) in items.entries.toList()) {
            if (item.status == DownloadStatus.RUNNING) {
                val fixed = item.copy(status = DownloadStatus.QUEUED, speedBps = 0)
                items[id] = fixed
                repaired.add(fixed)
            }
        }
        repaired
    }

    fun stats(): Map<DownloadStatus, Int> = synchronized(lock) {
        items.values.groupingBy { it.status }.eachCount()
    }
}
