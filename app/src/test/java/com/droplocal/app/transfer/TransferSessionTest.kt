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
}
