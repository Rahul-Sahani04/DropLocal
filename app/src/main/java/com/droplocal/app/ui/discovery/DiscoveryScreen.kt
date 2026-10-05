package com.droplocal.app.ui.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.nearby.NearbyEndpoint
import com.droplocal.app.ui.components.*

@Composable
fun DiscoveryScreen(
    endpoints: List<NearbyEndpoint>,
    scanning: Boolean,
    onSelect: (NearbyEndpoint) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
    title: String = "Send files",
    selectedSummary: String = "",
    error: String? = null,
    onScanQr: (() -> Unit)? = null,
) {
    ScreenColumn {
        ScreenHeading(title)
        if (selectedSummary.isNotBlank()) Text(selectedSummary)
        Text("Choose a nearby device", style = MaterialTheme.typography.titleLarge)
        if (error != null) StatusText(error, error = true)
        if (scanning) StatusText("Scanning for nearby devices…")
        if (endpoints.isEmpty()) Text("No devices found yet. Both phones need this updated DropLocal version. On the other phone, open Receive and make it visible. Keep Wi-Fi and Bluetooth on.")
        endpoints.distinctBy { it.id }.forEach { endpoint ->
            OutlinedButton(onClick = { onSelect(endpoint) }, modifier = ActionModifier) {
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(endpoint.name.ifBlank { "Android device" }, style = MaterialTheme.typography.titleMedium)
                    Text("Tap to compare connection codes", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedButton(onClick = onRescan, modifier = ActionModifier,
            enabled = !scanning || error != null) { Text(if (error != null) "Retry discovery" else "Scan again") }
        if (onScanQr != null) OutlinedButton(onClick = onScanQr, modifier = ActionModifier) { Text("Scan receiver QR") }
        TextButton(onClick = onBack, modifier = ActionModifier) { Text("Back") }
    }
}
