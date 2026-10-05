package com.droplocal.app.ui.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.transfer.*
import com.droplocal.app.ui.components.*
import java.text.NumberFormat

@Composable
fun TransferScreen(
    sessions: List<TransferSession>,
    peer: String,
    onDone: () -> Unit,
    onRetry: (String) -> Unit,
    onCancelAll: () -> Unit,
    canRetry: (TransferSession) -> Boolean = { false },
    onOpen: ((TransferSession) -> Unit)? = null,
    onShare: ((TransferSession) -> Unit)? = null,
    error: String? = null,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    val ordered = orderedSessions(sessions)
    val expectedCount = maxOf(ordered.size, ordered.maxOfOrNull { it.batchCount } ?: 0)
    val active = ordered.any(::isActive)
    ScreenColumn {
        ScreenHeading(if (expectedCount > 1) "Transfer batch" else "Transfer")
        if (peer.isNotBlank()) Text(peer, style = MaterialTheme.typography.titleLarge)
        if (error != null) StatusText(error, error = true)
        if (ordered.isEmpty()) Text("No transfers in this batch.")
        else {
            val completed = ordered.count { it.status == TransferStatus.DONE }
            val failed = ordered.count { it.status == TransferStatus.FAILED }
            val cancelled = ordered.count { it.status == TransferStatus.CANCELLED }
            StatusText("$completed of $expectedCount delivered · $failed failed · $cancelled cancelled")
            val incompleteOffers = ordered.size < expectedCount
            val declaredTotal = ordered.firstOrNull()?.batchSize ?: -1L
            Text("Batch size: ${formatBytes(if (incompleteOffers) declaredTotal else batchSize(ordered))}")
            val aggregate = if (incompleteOffers) null else aggregateProgress(ordered)
            if (active || aggregate != null) TransferProgress(aggregate)
            else Text("Batch progress unavailable")
            if (!active && incompleteOffers) StatusText("These files are finished. Remaining files need a separate offer and your approval.")
            else if (!active && completed == ordered.size) StatusText("All transfers complete")
            else if (!active) StatusText("Batch finished with incomplete transfers", error = failed > 0)
            ordered.forEach { session ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(session.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (session.direction == TransferDirection.SENT) "To ${session.peer.ifBlank { peer }}" else "From ${session.peer.ifBlank { peer }}")
                        StatusText(statusLabel(session), error = session.status == TransferStatus.FAILED)
                        if (session.error != null) StatusText(session.error, error = true)
                        TransferProgress(displayProgress(session))
                        val verb = if (session.direction == TransferDirection.SENT) "Sent" else "Received"
                        Text("$verb ${formatBytes(session.bytes.coerceAtLeast(0))} / ${formatBytes(session.size)}")
                        if (session.status == TransferStatus.RUNNING) {
                            Text("Session average: ${fmtSpeed(session.speedBps)}", style = MaterialTheme.typography.bodySmall)
                            if (session.size > 0 && session.bytes < session.size && session.speedBps.isFinite() && session.speedBps > 0) {
                                Text("Approx. ${formatSeconds((session.size - session.bytes) / session.speedBps)} remaining", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (session.status == TransferStatus.DONE) {
                            Text("Session time: ${formatSeconds(session.elapsedSec)}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (hasUsableOutput(session)) {
                            if (onOpen != null) OutlinedButton(onClick = { onOpen(session) }, modifier = ActionModifier) { Text("Open") }
                            if (onShare != null) OutlinedButton(onClick = { onShare(session) }, modifier = ActionModifier) { Text("Share") }
                        }
                        if (session.direction == TransferDirection.SENT && !session.isText &&
                            session.status == TransferStatus.FAILED && canRetry(session)) {
                            Button(onClick = { onRetry(session.id) }, modifier = ActionModifier) { Text("Retry file") }
                        }
                    }
                }
            }
        }
        if (active) OutlinedButton(onClick = { confirmCancel = true }, modifier = ActionModifier) { Text("Cancel all transfers") }
        else Button(onClick = onDone, modifier = ActionModifier) { Text("Home") }
    }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Cancel this batch?") },
        text = { Text("Stop all queued and active transfers. Completed files are kept.") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; onCancelAll() }) { Text("Cancel all") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep transferring") } },
    )
}

@Composable
private fun TransferProgress(progress: Float?) {
    if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    else {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Text(NumberFormat.getPercentInstance().format(progress), style = MaterialTheme.typography.bodySmall)
    }
}
