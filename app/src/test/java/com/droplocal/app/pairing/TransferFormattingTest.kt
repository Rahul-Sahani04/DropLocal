package com.droplocal.app.pairing

import com.droplocal.app.transfer.*
import com.droplocal.app.ui.transfer.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TransferFormattingTest {
    private fun session(id: String, size: Long, status: TransferStatus, bytes: Long = 0) =
        TransferSession(id = id, name = id, size = size, status = status, bytes = bytes)

    @Test fun formatsSmallUnknownAndLargeSizesWithoutZeroMb() {
        assertEquals("Unknown size", formatBytes(-1, Locale.US))
        assertEquals("0 B", formatBytes(0, Locale.US))
        assertEquals("512 B", formatBytes(512, Locale.US))
        assertEquals("1 KiB", formatBytes(1024, Locale.US))
        assertEquals("1 GiB", formatBytes(1_073_741_824, Locale.US))
        assertEquals("1,5 KiB", formatBytes(1536, Locale.GERMANY))
        assertEquals("—", fmtSpeed(Double.NaN))
    }

    @Test fun completedZeroAndUnknownSizeAre100Percent() {
        assertEquals(1f, displayProgress(session("empty", 0, TransferStatus.DONE))!!, 0f)
        assertEquals(1f, displayProgress(session("unknown", -1, TransferStatus.DONE))!!, 0f)
        assertNull(displayProgress(session("unknown", -1, TransferStatus.RUNNING)))
        assertNull(displayProgress(session("empty", 0, TransferStatus.RUNNING)))
    }

    @Test fun showsActiveFirstNotLastCompletedFileAndDeduplicates() {
        val running = session("large", 100, TransferStatus.RUNNING, 10)
        val done = session("small", 1, TransferStatus.DONE, 1)
        assertEquals(listOf(running, done), orderedSessions(listOf(done, running, done)))
        assertEquals(11f / 101f, aggregateProgress(listOf(running, done))!!, 0.0001f)
    }

    @Test fun aggregateIsHonestAboutUnknownAndEmptySizes() {
        assertNull(aggregateProgress(emptyList()))
        assertNull(aggregateProgress(listOf(session("unknown", -1, TransferStatus.RUNNING))))
        assertEquals(1f, aggregateProgress(listOf(session("empty", 0, TransferStatus.DONE)))!!, 0f)
        assertNull(aggregateProgress(listOf(session("empty", 0, TransferStatus.CANCELLED))))
        assertEquals(-1L, batchSize(listOf(session("unknown", -1, TransferStatus.DONE))))
        assertEquals(Long.MAX_VALUE, batchSize(listOf(session("a", Long.MAX_VALUE, TransferStatus.DONE), session("b", 1, TransferStatus.DONE))))
    }

    @Test fun directionAndOutputActionsAreTruthful() {
        val sending = session("a", 1, TransferStatus.SAVING)
        assertEquals("Waiting for receiver to save", statusLabel(sending))
        assertEquals("Saving received file", statusLabel(sending.copy(direction = TransferDirection.RECEIVED)))
        val done = sending.copy(status = TransferStatus.DONE, outputUri = "content://downloads/1")
        assertTrue(hasUsableOutput(done))
        assertFalse(hasUsableOutput(done.copy(isText = true)))
        assertFalse(hasUsableOutput(done.copy(outputUri = "file:///unsafe")))
        assertFalse(hasUsableOutput(done.copy(status = TransferStatus.FAILED)))
    }
}
