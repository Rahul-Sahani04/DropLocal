package com.droplocal.app.ui.transfer

import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 0) return "Unknown size"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var amount = bytes.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    val number = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (unit == 0) 0 else 1
        minimumFractionDigits = 0
    }
    return "${number.format(amount)} ${units[unit]}"
}

// Preserve existing callers while no longer rounding small files to zero MB.
fun fmtMB(b: Long): String = formatBytes(b)
fun fmtSpeed(bps: Double): String = if (bps.isFinite() && bps > 0) "${formatBytes(bps.toLong())}/s" else "—"
fun formatSeconds(seconds: Double, locale: Locale = Locale.getDefault()): String {
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return "${number.format(if (seconds.isFinite()) max(0.0, seconds) else 0.0)} s"
}

fun isActive(session: TransferSession): Boolean = session.status in setOf(
    TransferStatus.QUEUED, TransferStatus.WAITING, TransferStatus.RUNNING, TransferStatus.SAVING,
)

fun orderedSessions(sessions: List<TransferSession>): List<TransferSession> = sessions.distinctBy { it.id }.sortedBy {
    when (it.status) {
        TransferStatus.RUNNING -> 0
        TransferStatus.SAVING -> 1
        TransferStatus.WAITING -> 2
        TransferStatus.QUEUED -> 3
        else -> 4
    }
}

fun displayProgress(session: TransferSession): Float? = when {
    session.status == TransferStatus.DONE -> 1f
    session.size <= 0 -> if (isActive(session)) null else 0f
    else -> (session.bytes.toDouble() / session.size).toFloat().coerceIn(0f, 1f)
}

fun aggregateProgress(sessions: List<TransferSession>): Float? {
    if (sessions.isEmpty()) return null
    if (sessions.all { it.status == TransferStatus.DONE }) return 1f
    if (sessions.any { it.size < 0 }) return null
    val total = sessions.sumOf { it.size.toDouble() }
    if (total <= 0) return null
    return (sessions.sumOf {
        if (it.status == TransferStatus.DONE) it.size.toDouble()
        else it.bytes.coerceIn(0, it.size).toDouble()
    } / total).toFloat().coerceIn(0f, 1f)
}

fun batchSize(sessions: List<TransferSession>): Long {
    if (sessions.any { it.size < 0 }) return -1
    return sessions.fold(0L) { sum, item ->
        if (Long.MAX_VALUE - sum < item.size) Long.MAX_VALUE else sum + item.size
    }
}

fun statusLabel(session: TransferSession): String = when (session.status) {
    TransferStatus.QUEUED -> "Queued · Preparing"
    TransferStatus.WAITING -> if (session.direction == TransferDirection.SENT) "Waiting for receiver approval" else "Waiting to receive"
    TransferStatus.RUNNING -> if (session.direction == TransferDirection.SENT) "Sending" else "Receiving"
    TransferStatus.SAVING -> if (session.direction == TransferDirection.RECEIVED) "Saving received file" else "Waiting for receiver to save"
    TransferStatus.DONE -> if (session.direction == TransferDirection.SENT) "Delivered" else if (session.isText) "Text received" else "Saved"
    TransferStatus.FAILED -> "Failed"
    TransferStatus.CANCELLED -> "Cancelled"
}

fun hasUsableOutput(session: TransferSession): Boolean = session.status == TransferStatus.DONE &&
    !session.isText && session.outputUri?.let { it.startsWith("content://") && it.length > 10 } == true
