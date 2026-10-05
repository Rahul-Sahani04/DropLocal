package com.droplocal.app.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real ContentResolver/MediaStore tests. Only synthetic fixture files are touched. */
@RunWith(AndroidJUnit4::class)
class FileRepositoryDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun receivedBytesArePublishedInDownloadsAndReadableByReturnedUri() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val name = "droplocal-test-${UUID.randomUUID()}.txt"
        val source = File.createTempFile("receive_fixture_", ".txt", context.cacheDir)
        val bytes = "Synthetic received file 😀".toByteArray(Charsets.UTF_8)
        source.writeBytes(bytes)
        var saved: SavedFile? = null
        try {
            saved = FileRepository(context).saveReceived(Uri.fromFile(source), name, "text/plain")
            assertEquals("content", saved.uri.scheme)
            assertEquals(bytes.size.toLong(), saved.size)
            context.contentResolver.openInputStream(saved.uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
            context.contentResolver.query(saved.uri, arrayOf(MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.MIME_TYPE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("text/plain", it.getString(1))
            }
        } finally { saved?.let { context.contentResolver.delete(it.uri, null, null) }; source.delete() }
    }

    @Test fun unreadableSourceRollsBackPendingDownloadsRow() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val name = "droplocal-test-${UUID.randomUUID()}.txt"
        try {
            FileRepository(context).saveReceived(Uri.parse("content://invalid.droplocal.fixture/missing"), name, "text/plain")
            fail("Unusable sources must not be declared saved")
        } catch (_: Exception) { }
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { assertEquals(0, it.count) }
    }

    @Test fun invalidDestinationPathIsRejected() = runBlocking {
        val source = File.createTempFile("inspect_fixture_", ".txt", context.cacheDir)
        try {
            source.writeText("fixture")
            try {
                FileRepository(context).saveReceived(Uri.fromFile(source), "../escape.txt", "text/plain")
                fail("Traversal must be rejected")
            } catch (_: IllegalArgumentException) { }
        } finally { source.delete() }
    }
}
