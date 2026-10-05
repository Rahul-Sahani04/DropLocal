package com.droplocal.app.storage

import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import org.junit.Assert.*
import org.junit.Test

class HistoryStoreTest {
    @Test fun retryUpsertsByIdRatherThanAddingDuplicateKeys() {
        val failed = TransferSession(id = "same", name = "a", size = 10, status = TransferStatus.FAILED)
        val done = failed.copy(status = TransferStatus.DONE, outputUri = "content://downloads/1")
        val records = HistoryRecords.upsert(listOf(failed, failed), done)
        assertEquals(listOf(done), records)
    }

    @Test fun roundTripsNewFieldsAndEscapedStrings() {
        val session = TransferSession(id = "id", name = "a\"b", size = -1, isText = true,
            outputUri = "content://downloads/42", batchId = "batch", error = "Cannot save\nretry",
            status = TransferStatus.CANCELLED, endedAt = 99L)
        assertEquals(listOf(session), HistoryRecords.decode(HistoryRecords.encode(listOf(session))))
    }

    @Test fun loadsOldRecordsAndDeduplicatesNewestFirst() {
        val raw = """[{"id":"old","name":"one","size":0},{"id":"old","name":"stale"},
            {"id":"broken","status":"BOGUS"},{"id":"good","name":"two"}]"""
        val result = HistoryRecords.decode(raw)
        assertEquals(listOf("old", "good"), result.map { it.id })
        assertEquals("one", result.first().name)
        assertNull(result.first().outputUri)
        assertFalse(result.first().isText)
        assertEquals(-1, result.last().size)
    }

    @Test fun boundsDistinctHistoryToTen() {
        val sessions = (0..30).map { TransferSession(id = "$it", name = "a", size = 0) }
        val result = HistoryRecords.decode(HistoryRecords.encode(sessions))
        assertEquals(10, result.size)
        assertEquals((0..9).map { "$it" }, result.map { it.id })
        assertTrue(HistoryRecords.decode("not JSON").isEmpty())
    }
}
