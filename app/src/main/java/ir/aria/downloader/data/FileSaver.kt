package ir.aria.downloader.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import ir.aria.downloader.model.DownloadItem
import ir.aria.downloader.net.FileNames
import java.io.File

/** Moves finished `.part` files into the public Downloads folder and can delete/open them. */
object FileSaver {

    data class Saved(val uri: String, val displayName: String)

    const val SUB_FOLDER = "AllDown"

    /**
     * Copies [temp] into `Downloads/AllDown/` and returns the resulting uri.
     * Uses MediaStore on API 29+ (no permission needed) and direct file IO below that.
     */
    fun save(context: Context, temp: File, suggestedName: String, mime: String, taken: Set<String>): Saved {
        val name = FileNames.ensureUnique(suggestedName, taken)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, temp, name, mime)
        } else {
            saveToPublicDir(temp, name)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, temp: File, name: String, mime: String): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("cannot create file in Downloads")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output, 128 * 1024) }
            } ?: throw IllegalStateException("cannot open output stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val finalName = queryDisplayName(context, uri) ?: name
            return Saved(uri.toString(), finalName)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDir(temp: File, name: String): Saved {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_FOLDER
        )
        if (!dir.exists()) dir.mkdirs()
        var target = File(dir, name)
        var index = 1
        while (target.exists()) {
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            target = File(dir, "$stem ($index)$ext")
            index++
        }
        temp.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
        }
        return Saved(target.absolutePath, target.name)
    }

    /** Renames an already saved file. Returns the new uri/path, or null when it failed. */
    fun rename(context: Context, item: DownloadItem, newName: String): String? {
        val raw = item.fileUri ?: return null
        return try {
            if (raw.startsWith("content://")) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                val updated = context.contentResolver.update(Uri.parse(raw), values, null, null)
                if (updated > 0) raw else null
            } else {
                val file = File(raw)
                if (!file.exists()) return null
                val target = File(file.parentFile, newName)
                if (target.exists()) return null
                if (file.renameTo(target)) target.absolutePath else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(context: Context, item: DownloadItem): Boolean {
        val uri = item.fileUri ?: return false
        return try {
            if (uri.startsWith("content://")) {
                context.contentResolver.delete(Uri.parse(uri), null, null) > 0
            } else {
                File(uri).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun openIntent(context: Context, item: DownloadItem): Intent? {
        val raw = item.fileUri ?: return null
        val uri: Uri = if (raw.startsWith("content://")) {
            Uri.parse(raw)
        } else {
            val file = File(raw)
            if (!file.exists()) return null
            // A path outside the configured FileProvider roots must never crash the caller
            // (this runs while building a notification).
            runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            }.getOrNull() ?: return null
        }
        val mime = if (item.mimeType.isBlank()) "application/octet-stream" else item.mimeType
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** True when the file behind [item] still exists on the device. */
    fun exists(context: Context, item: DownloadItem): Boolean {
        val raw = item.fileUri ?: return false
        return try {
            if (raw.startsWith("content://")) {
                context.contentResolver.query(Uri.parse(raw), arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                    ?.use { it.moveToFirst() } ?: false
            } else {
                File(raw).exists()
            }
        } catch (e: Exception) {
            false
        }
    }
}
