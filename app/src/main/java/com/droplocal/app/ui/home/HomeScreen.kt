package com.droplocal.app.ui.home

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.droplocal.app.ui.components.*

@Composable
fun HomeScreen(
    discoverable: String,
    onSendFile: () -> Unit,
    onSendText: () -> Unit,
    onReceive: () -> Unit,
    onHistory: () -> Unit,
    onDisconnect: (() -> Unit)? = null,
    onScanQr: (() -> Unit)? = null,
    onTransfers: (() -> Unit)? = null,
) {
    ScreenColumn {
        Text("DropLocal", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() })
        Text("Nearby. Private. Direct.")
        Button(onClick = onSendFile, modifier = ActionModifier) { Text("Send files") }
        Button(onClick = onSendText, modifier = ActionModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )) { Text("Send text") }
        OutlinedButton(onClick = onReceive, modifier = ActionModifier) { Text("Receive") }
        Text("Make this device visible and approve incoming transfers.",
            style = MaterialTheme.typography.bodyMedium)
        if (onScanQr != null) OutlinedButton(onClick = onScanQr, modifier = ActionModifier) { Text("Scan receiver QR") }
        TextButton(onClick = onHistory, modifier = ActionModifier) { Text("History") }
        StatusText(discoverable.ifBlank { "Not visible · Not connected" })
        if (onTransfers != null) Button(onClick = onTransfers, modifier = ActionModifier) { Text("View active transfers") }
        if (onDisconnect != null) OutlinedButton(onClick = onDisconnect, modifier = ActionModifier) { Text("Disconnect") }
    }
}
