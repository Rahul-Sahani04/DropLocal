package com.droplocal.app.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class TransferDirection { SENT, RECEIVED }
enum class TransferStatus {
    QUEUED, WAITING, RUNNING, SAVING, DONE, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

data class TransferSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val mime: String = "application/octet-stream",
    val direction: TransferDirection = TransferDirection.SENT,
    val peer: String = "",
    val bytes: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val error: String? = null,
    val batchId: String? = null,
    val outputUri: String? = null,
    val isText: Boolean = false,
    val batchIndex: Int = 1,
    val batchCount: Int = 1,
    val batchSize: Long = -1,
) {
    val isIndeterminate: Boolean get() = size < 0 && !status.isTerminal
    val progress: Float get() = when {
        status == TransferStatus.DONE -> 1f
        size <= 0 -> 0f
        else -> (bytes.toDouble() / size).coerceIn(0.0, 1.0).toFloat()
    }
    val elapsedSec: Double get() = ((endedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(0L) / 1000.0
    val speedBps: Double get() {
        val e = elapsedSec.coerceAtLeast(0.2)
        return bytes / e
    }

    internal fun withProgress(transferred: Long, total: Long?): TransferSession {
        if (status.isTerminal) return this
        return copy(
            bytes = maxOf(bytes, transferred.coerceAtLeast(0)),
            size = total?.takeIf { it >= 0 } ?: size,
            status = if (status == TransferStatus.SAVING) status else TransferStatus.RUNNING,
        )
    }
}

/** Single-active-file queue; batch presents as queued items (PRD §14). */
class TransferQueue {
    private val _items = MutableStateFlow<List<TransferSession>>(emptyList())
    val items: StateFlow<List<TransferSession>> = _items

    @Synchronized fun enqueue(s: TransferSession) {
        val existing = _items.value.firstOrNull { it.id == s.id }
        if (existing?.status?.isTerminal == true) return
        _items.value = if (existing == null) _items.value + s else _items.value.map {
            if (it.id == s.id) s else it
        }
    }
    @Synchronized fun update(id: String, fn: (TransferSession) -> TransferSession): TransferSession? {
        val current = _items.value.firstOrNull { it.id == id } ?: return null
        if (current.status.isTerminal) return null
        val next = fn(current)
        if (next == current) return null
        _items.value = _items.value.map { if (it.id == id) next else it }
        return next
    }
    @Synchronized fun retry(id: String): TransferSession? {
        val current = _items.value.firstOrNull { it.id == id } ?: return null
        if (current.status != TransferStatus.FAILED && current.status != TransferStatus.CANCELLED) return null
        val next = current.copy(status = TransferStatus.QUEUED, bytes = 0L, error = null,
            outputUri = null, startedAt = System.currentTimeMillis(), endedAt = null)
        _items.value = _items.value.map { if (it.id == id) next else it }
        return next
    }
    fun active(): TransferSession? = _items.value.firstOrNull {
        it.status == TransferStatus.RUNNING || it.status == TransferStatus.SAVING
    } ?: _items.value.firstOrNull { !it.status.isTerminal }

    fun latestBatch(): List<TransferSession> {
        val latest = _items.value.lastOrNull() ?: return emptyList()
        return latest.batchId?.let { batch -> _items.value.filter { it.batchId == batch } } ?: listOf(latest)
    }

    /** Retain live work and the current batch, plus a bounded tail of older results. */
    @Synchronized fun pruneCompleted(keep: Int = 100) {
        val latestIds = latestBatch().map { it.id }.toSet()
        val retained = _items.value.filter { it.status.isTerminal && it.id !in latestIds }
            .takeLast(keep.coerceAtLeast(0)).map { it.id }.toSet()
        _items.value = _items.value.filter {
            !it.status.isTerminal || it.id in latestIds || it.id in retained
        }
    }
}
