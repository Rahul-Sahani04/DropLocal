package com.droplocal.app.transfer

import com.droplocal.app.storage.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class TransferRepository internal constructor(
    private val record: suspend (TransferSession) -> Unit,
    scope: CoroutineScope,
) {
    constructor(history: HistoryStore) : this(history::record, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val queue = TransferQueue()
    private val finalStates = Channel<TransferSession>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (snapshot in finalStates) {
                try { record(snapshot) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A history failure must not stop subsequent records. */ }
            }
        }
    }

    fun enqueue(session: TransferSession) = queue.enqueue(session)

    fun waiting(id: String) { queue.update(id) {
        if (it.status == TransferStatus.QUEUED) it.copy(status = TransferStatus.WAITING) else it
    } }

    fun running(id: String) { queue.update(id) {
        if (it.status == TransferStatus.SAVING) it else it.copy(status = TransferStatus.RUNNING)
    } }

    fun saving(id: String) { queue.update(id) { it.copy(status = TransferStatus.SAVING) } }

    fun progress(id: String, bytes: Long, totalBytes: Long? = null) {
        queue.update(id) { it.withProgress(bytes, totalBytes) }
    }

    @Synchronized fun complete(id: String, outputUri: String? = null) {
        finish(queue.update(id) { it.copy(status = TransferStatus.DONE,
            outputUri = outputUri ?: it.outputUri, error = null, endedAt = System.currentTimeMillis()) })
    }

    @Synchronized fun fail(id: String, error: String) {
        finish(queue.update(id) { it.copy(status = TransferStatus.FAILED, error = error,
            endedAt = System.currentTimeMillis()) })
    }

    @Synchronized fun retry(id: String) { queue.retry(id) }

    @Synchronized fun cancel(id: String) {
        finish(queue.update(id) { it.copy(status = TransferStatus.CANCELLED,
            endedAt = System.currentTimeMillis()) })
    }

    private fun finish(snapshot: TransferSession?) {
        snapshot ?: return
        finalStates.trySend(snapshot)
        queue.pruneCompleted()
    }
}
