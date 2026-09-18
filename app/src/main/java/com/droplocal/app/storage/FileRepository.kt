package com.droplocal.app.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File

data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)

class FileRepository(private val context: Context) {
    fun inspect(uri: Uri): PickedFile {
        val cr: ContentResolver = context.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        if (size < 0) {
            try {
                cr.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
            } catch (_: Exception) { }
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        return PickedFile(uri, name, size.coerceAtLeast(0L), mime)
    }

    /** Copy SAF uri bytes into app cache for Nearby file payload. */
    fun copyToCache(uri: Uri, name: String): File {
        val out = File(context.cacheDir, "send_${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { outs -> ins.copyTo(outs) }
        }
        return out
    }

    /** Persist received file bytes into Downloads (predictable location, PRD §1). */
    fun saveToDownloads(src: File, name: String): File {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        var dest = File(dl, name)
        var i = 1
        while (dest.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            dest = File(dl, if (ext.isEmpty() || ext == name) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        src.copyTo(dest, overwrite = true)
        return dest
    }
}
