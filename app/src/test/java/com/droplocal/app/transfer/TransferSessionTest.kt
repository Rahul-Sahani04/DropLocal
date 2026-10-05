package com.droplocal.app.transfer

import org.junit.Assert.*
import org.junit.Test

class TransferSessionTest {
    @Test fun progressMath() {
        val s = TransferSession(name = "a.pdf", size = 100, bytes = 82)
        assertEquals(0.82f, s.progress, 0.001f)
    }

    @Test fun zeroSizeSafe() {
        val s = TransferSession(name = "e", size = 0, bytes = 0)
        assertEquals(0f, s.progress, 0f)
        assertEquals(1f, s.copy(status = TransferStatus.DONE).progress, 0f)
        assertTrue(s.copy(size = -1).isIndeterminate)
        assertFalse(s.copy(size = -1, status = TransferStatus.DONE).isIndeterminate)
    }

    @Test fun queueSingleActive() {
        val q = TransferQueue()
        val a = TransferSession(name = "a", size = 10)
        val b = TransferSession(name = "b", size = 10)
        q.enqueue(a); q.enqueue(b)
        assertEquals(a.id, q.active()?.id)
        q.update(a.id) { it.copy(status = TransferStatus.DONE) }
        assertEquals(b.id, q.active()?.id)
    }

    @Test fun queueUpsertsAndProtectsTerminalIds() {
        val q = TransferQueue()
        val original = TransferSession(id = "id", name = "a", size = 10)
        q.enqueue(original)
        q.enqueue(original.copy(peer = "updated"))
        assertEquals(1, q.items.value.size)
        assertEquals("updated", q.items.value.single().peer)
        q.update("id") { it.copy(status = TransferStatus.DONE) }
        q.enqueue(original)
        q.update("id") { it.copy(status = TransferStatus.RUNNING) }
        assertNull(q.retry("id"))
        assertEquals(TransferStatus.DONE, q.items.value.single().status)
    }

    @Test fun latestBatchSurvivesPruningAndActiveWinsOverQueued() {
        val q = TransferQueue()
        q.enqueue(TransferSession(id = "older", name = "old", size = 0, status = TransferStatus.DONE))
        q.enqueue(TransferSession(id = "first", name = "a", size = 0, batchId = "batch"))
        q.enqueue(TransferSession(id = "second", name = "b", size = 0, batchId = "batch", status = TransferStatus.SAVING))
        assertEquals("second", q.active()?.id)
        assertEquals(listOf("first", "second"), q.latestBatch().map { it.id })
        q.pruneCompleted(0)
        assertEquals(listOf("first", "second"), q.items.value.map { it.id })
    }
}
