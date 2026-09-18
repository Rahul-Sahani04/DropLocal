package com.droplocal.app.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class TransferDirection { SENT, RECEIVED }
enum class TransferStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

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
) {
    val progress: Float get() = if (size <= 0) 0f else (bytes.toFloat() / size).coerceIn(0f, 1f)
    val elapsedSec: Double get() = ((endedAt ?: System.currentTimeMillis()) - startedAt) / 1000.0
    val speedBps: Double get() {
        val e = elapsedSec.coerceAtLeast(0.2)
        return bytes / e
    }
}

/** Single-active-file queue; batch presents as queued items (PRD §14). */
class TransferQueue {
    private val _items = MutableStateFlow<List<TransferSession>>(emptyList())
    val items: StateFlow<List<TransferSession>> = _items

    fun enqueue(s: TransferSession) { _items.value = _items.value + s }
    fun update(id: String, fn: (TransferSession) -> TransferSession) {
        _items.value = _items.value.map { if (it.id == id) fn(it) else it }
    }
    fun active(): TransferSession? = _items.value.firstOrNull {
        it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING
    }
}
