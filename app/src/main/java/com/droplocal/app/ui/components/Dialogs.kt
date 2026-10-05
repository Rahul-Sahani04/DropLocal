package com.droplocal.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.nearby.IncomingFile
import com.droplocal.app.nearby.IncomingText
import com.droplocal.app.ui.transfer.formatBytes

@Composable
fun IncomingFileDialog(info: IncomingFile, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { ScreenHeading("Incoming file offer") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${info.sender} wants to send")
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Text("Size: ${formatBytes(info.size)}")
                if (info.batchCount > 1) Text("File ${info.batchIndex} of ${info.batchCount} · Batch: ${formatBytes(info.batchSize)}")
                Text("Type: ${info.mime}", style = MaterialTheme.typography.bodySmall)
                Text("No file bytes are sent until you accept. Only accept files you expect from this sender.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept file") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Reject") } },
    )
}

@Composable
fun IncomingTextDialog(
    info: IncomingText,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
    onShare: ((String) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ScreenHeading("Text received") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From ${info.sender}")
                SelectionContainer { Text(info.text) }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { onCopy(info.text) }) { Text("Copy") }
                if (onShare != null) TextButton(onClick = { onShare(info.text) }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row { Text("$label  ${if (ok) "✓ Ready" else "! Required"}") }
}

@Composable
fun PermissionChecklist(
    bluetooth: Boolean,
    nearby: Boolean,
    location: Boolean,
    notifications: Boolean,
) {
    Column {
        StatusRow("BLUETOOTH", bluetooth)
        StatusRow("NEARBY DEVICES", nearby)
        StatusRow("LOCATION", location)
        StatusRow("NOTIFICATIONS", notifications)
    }
}
