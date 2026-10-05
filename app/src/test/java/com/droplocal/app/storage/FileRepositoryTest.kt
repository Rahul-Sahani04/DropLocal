package com.droplocal.app.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class FileRepositoryTest {
    @Test fun normalizesSafeBasenames() {
        assertEquals("report.pdf", SafeFileNames.basename("  report.pdf  "))
        assertEquals("file.txt", SafeFileNames.basename("ｆｉｌｅ.txt"))
        assertEquals("report (2).pdf", SafeFileNames.numbered("report.pdf", 2))
        assertEquals(".hidden (1)", SafeFileNames.numbered(".hidden", 1))
    }

    @Test fun rejectsPathTraversalControlsAndOversizedNames() {
        listOf("", " ", ".", "..", "../report", "a/b", "a\\b", "/absolute",
            "a\u0000b", "a\nb", "a\u202eb", "a:b", "fullwidth／slash", "file.", "é".repeat(101))
            .forEach { invalid ->
                try {
                    SafeFileNames.basename(invalid)
                    fail("Accepted unsafe filename: $invalid")
                } catch (_: IllegalArgumentException) { }
            }
    }

    @Test fun containmentDoesNotPermitEscapes() {
        val root = File(System.getProperty("java.io.tmpdir"), "droplocal-name-tests")
        assertEquals(root.canonicalFile, SafeFileNames.containedFile(root, "safe.zip").parentFile)
        try {
            SafeFileNames.containedFile(root, "../outside")
            fail("Traversal accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun copiesActualBytesIncludingEmptyContent() = runTest {
        val bytes = ByteArray(40001) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyCancellable(ByteArrayInputStream(bytes), output))
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(0L, copyCancellable(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream()))
    }

    @Test fun interruptedSourceDoesNotReturnSuccess() = runTest {
        val input = object : ByteArrayInputStream(ByteArray(20)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.io.IOException("read failed")
        }
        try {
            copyCancellable(input, ByteArrayOutputStream())
            fail("Read failure swallowed")
        } catch (e: java.io.IOException) { assertEquals("read failed", e.message) }
    }

    @Test fun copyCooperatesWithCancellationBetweenChunks() = runTest {
        var returned = false
        var cancelled = false
        val job = launch {
            val context = currentCoroutineContext()
            val input = object : ByteArrayInputStream(ByteArray(40001)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    return super.read(b, off, len).also { context.cancel() }
                }
            }
            try {
                copyCancellable(input, ByteArrayOutputStream())
                returned = true
            } catch (_: CancellationException) { cancelled = true }
        }
        job.join()
        assertFalse(returned)
        assertTrue(cancelled)
    }
}
