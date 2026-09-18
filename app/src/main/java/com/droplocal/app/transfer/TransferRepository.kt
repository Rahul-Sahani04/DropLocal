package com.droplocal.app.transfer

import com.droplocal.app.storage.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TransferRepository(private val history: HistoryStore) {
    val queue = TransferQueue()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun enqueue(session: TransferSession) = queue.enqueue(session)

    fun progress(id: String, bytes: Long) {
        queue.update(id) { it.copy(bytes = bytes, status = TransferStatus.RUNNING) }
    }

    fun complete(id: String) {
        queue.update(id) { it.copy(bytes = it.size, status = TransferStatus.DONE, endedAt = System.currentTimeMillis()) }
        scope.launch {
            queue.items.value.firstOrNull { it.id == id }?.let { history.record(it) }
        }
    }

    fun fail(id: String, error: String) {
        queue.update(id) { it.copy(status = TransferStatus.FAILED, error = error, endedAt = System.currentTimeMillis()) }
        scope.launch {
            queue.items.value.firstOrNull { it.id == id }?.let { history.record(it) }
        }
    }

    fun retry(id: String) {
        queue.update(id) { it.copy(status = TransferStatus.QUEUED, bytes = 0L, error = null, startedAt = System.currentTimeMillis(), endedAt = null) }
    }

    fun cancel(id: String) {
        queue.update(id) { it.copy(status = TransferStatus.CANCELLED, endedAt = System.currentTimeMillis()) }
    }
}
