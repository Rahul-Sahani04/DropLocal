package com.droplocal.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.nearby.IncomingFile
import com.droplocal.app.nearby.IncomingText

@Composable
fun IncomingFileDialog(info: IncomingFile, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("INCOMING TRANSFER") },
        text = {
            Column {
                Text("${info.sender} wants to send")
                Text(info.name, style = MaterialTheme.typography.titleMedium)
                Text("%.1f MB".format(info.size / 1_048_576.0))
                Text(info.mime, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(2.dp))
                Text("Authenticated session · accept to receive", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("ACCEPT") } },
        dismissButton = { TextButton(onClick = onReject) { Text("REJECT") } },
    )
}

@Composable
fun IncomingTextDialog(info: IncomingText, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TEXT RECEIVED") },
        text = {
            Column {
                Text("From ${info.sender}")
                Text("“${info.text.take(500)}”")
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(info.text) }) { Text("COPY") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
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
