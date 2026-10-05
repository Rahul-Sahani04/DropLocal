package com.droplocal.app.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.util.UUID

data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String)
data class SavedFile(val uri: Uri, val size: Long)

/** Peer-controlled display names are never paths; normalization happens before validation. */
internal object SafeFileNames {
    fun basename(raw: String): String {
        val name = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
        require(name.isNotEmpty() && name != "." && name != "..") { "Invalid filename" }
        require(name.toByteArray(Charsets.UTF_8).size <= 200) { "Filename is too long" }
        require(name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() ||
            Character.getType(it) == Character.FORMAT.toInt() }) { "Filename must be a safe basename" }
        require(!name.endsWith('.')) { "Invalid filename" }
        return name
    }

    fun containedFile(directory: File, name: String): File {
        val root = directory.canonicalFile
        val result = File(root, basename(name)).canonicalFile
        require(result.parentFile == root) { "Filename escapes destination" }
        return result
    }

    fun numbered(name: String, index: Int): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return "${name.substring(0, dot)} ($index)${name.substring(dot)}"
    }
}

class FileRepository(private val context: Context) {
    suspend fun inspect(uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) {
            try { cr.openAssetFileDescriptor(uri, "r")?.use { size = it.length } }
            catch (_: java.io.IOException) { /* Some document providers cannot report a size. */ }
        }
        val mime = cr.getType(uri)?.takeIf { it.length <= 127 && MIME.matches(it) } ?: "application/octet-stream"
        PickedFile(uri, SafeFileNames.basename(name), size.coerceAtLeast(-1L), mime)
    }

    /** Only locally generated identities are used for temporary files. Caller owns successful files. */
    suspend fun copyToCache(uri: Uri, name: String): File {
        var completed: File? = null
        try {
            return withContext(Dispatchers.IO) { prepareCache(uri, name).also { completed = it } }
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { completed?.delete() }
            throw e
        }
    }

    private suspend fun prepareCache(uri: Uri, name: String): File {
        SafeFileNames.basename(name)
        val directory = File(context.cacheDir, "transfers")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare transfer cache" }
        val out = File.createTempFile(cachePrefix, ".tmp", directory)
        try {
            copySource(uri) { out.outputStream() }
            currentCoroutineContext().ensureActive()
            return out
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    /** Delete only this repository's owned outgoing cache files. */
    fun deleteCached(file: File) {
        val root = File(context.cacheDir, "transfers").canonicalFile
        if (file.canonicalFile.parentFile == root && file.name.startsWith("send_") && file.name.endsWith(".tmp")) {
            file.delete()
        }
    }

    /** Only known outgoing cache identities from older processes are removed, never saved files. */
    suspend fun cleanupOrphanedCache() = withContext(Dispatchers.IO) {
        File(context.cacheDir, "transfers").listFiles()?.filter {
            it.isFile && it.name.startsWith("send_") && it.name.endsWith(".tmp") && !it.name.startsWith(cachePrefix)
        }?.forEach { it.delete() }
        context.cacheDir.listFiles()?.filter {
            it.isFile && Regex("send_[0-9]{13}_.+").matches(it.name)
        }?.forEach { it.delete() }
    }

    suspend fun saveReceived(source: Uri, name: String, mime: String): SavedFile {
        var completed: SavedFile? = null
        try {
            val saved = withContext(Dispatchers.IO) {
                val safeName = SafeFileNames.basename(name)
                require(mime.length <= 255 && MIME.matches(mime)) { "Invalid MIME type" }
                val destination = if (Build.VERSION.SDK_INT >= 29) {
                    saveModern(source, safeName, mime)
                } else {
                    saveLegacy(source, safeName)
                }
                completed = destination
                destination
            }
            return saved
        } catch (e: Throwable) {
            // withContext can cancel at its return boundary after the copy has succeeded.
            withContext(NonCancellable + Dispatchers.IO) {
                completed?.let { saved ->
                    try { context.contentResolver.delete(saved.uri, null, null) }
                    catch (cleanup: Exception) { e.addSuppressed(cleanup) }
                }
            }
            throw e
        }
    }

    @androidx.annotation.RequiresApi(29)
    private suspend fun saveModern(source: Uri, name: String, mime: String): SavedFile {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DropLocal")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val destination = checkNotNull(cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Cannot create Downloads entry"
        }
        try {
            val size = copySource(source) { checkNotNull(cr.openOutputStream(destination, "w")) {
                "Cannot open Downloads entry"
            } }
            currentCoroutineContext().ensureActive()
            check(cr.update(destination, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null) == 1) { "Cannot publish Downloads entry" }
            return SavedFile(destination, size)
        } catch (e: Throwable) {
            try { cr.delete(destination, null, null) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(source: Uri, name: String): SavedFile {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DropLocal")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create Downloads folder" }
        var destination = SafeFileNames.containedFile(directory, name)
        var index = 1
        while (!destination.createNewFile()) {
            destination = SafeFileNames.containedFile(directory, SafeFileNames.numbered(name, index++))
        }
        try {
            val size = copySource(source) { destination.outputStream() }
            currentCoroutineContext().ensureActive()
            return SavedFile(FileProvider.getUriForFile(context, "${context.packageName}.files", destination), size)
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }

    private suspend fun copySource(source: Uri, output: () -> OutputStream): Long {
        val input = checkNotNull(context.contentResolver.openInputStream(source)) { "Cannot open source file" }
        return input.use { ins -> output().use { outs -> copyCancellable(ins, outs) } }
    }

    private companion object {
        val MIME = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")
        val cachePrefix = "send_${UUID.randomUUID()}_"
    }
}

internal suspend fun copyCancellable(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        currentCoroutineContext().ensureActive()
        output.write(buffer, 0, count)
        total += count
    }
    currentCoroutineContext().ensureActive()
    output.flush()
    return total
}
