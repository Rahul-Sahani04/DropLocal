package com.droplocal.app.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.transfer.*
import com.droplocal.app.ui.components.*
import com.droplocal.app.ui.transfer.*
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    items: List<TransferSession>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    error: String? = null,
    onOpen: ((TransferSession) -> Unit)? = null,
    onShare: ((TransferSession) -> Unit)? = null,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val unique = items.distinctBy { it.id }.take(10)
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    ScreenColumn {
        ScreenHeading("History")
        Text("Last 10 local transfers · Message contents are not stored")
        if (error != null) StatusText(error, error = true)
        if (unique.isEmpty()) Text("No transfers yet. Send or receive a file or message to see its result here.")
        unique.forEach { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(session.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (session.direction == TransferDirection.SENT) "Sent to ${session.peer.ifBlank { "Nearby device" }}" else "Received from ${session.peer.ifBlank { "Nearby device" }}")
                    StatusText(statusLabel(session), error = session.status == TransferStatus.FAILED)
                    if (session.error != null) StatusText(session.error, error = true)
                    Text("${formatBytes(session.size)} · ${formatSeconds(session.elapsedSec)}", style = MaterialTheme.typography.bodySmall)
                    Text(dateFormat.format(Date(session.startedAt)), style = MaterialTheme.typography.bodySmall)
                    if (hasUsableOutput(session)) {
                        if (onOpen != null) OutlinedButton(onClick = { onOpen(session) }, modifier = ActionModifier) { Text("Open") }
                        if (onShare != null) OutlinedButton(onClick = { onShare(session) }, modifier = ActionModifier) { Text("Share") }
                    } else if (session.direction == TransferDirection.RECEIVED && session.status == TransferStatus.DONE && !session.isText) {
                        Text("Saved-file link unavailable", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        TextButton(onClick = { confirmClear = true }, enabled = unique.isNotEmpty(), modifier = ActionModifier) { Text("Clear history") }
        TextButton(onClick = onBack, modifier = ActionModifier) { Text("Back") }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("Remove transfer records from this phone. Saved files are not deleted.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } },
    )
}
