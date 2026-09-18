package com.droplocal.app.ui.discovery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.nearby.NearbyEndpoint
import com.droplocal.app.ui.theme.Surface

@Composable
fun DiscoveryScreen(
    endpoints: List<NearbyEndpoint>,
    scanning: Boolean,
    onSelect: (NearbyEndpoint) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("SEND FILE", style = MaterialTheme.typography.labelLarge)
        Text("Nearby Devices", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (endpoints.isEmpty()) {
            Text("No nearby devices yet.\nMake sure both phones have DropLocal open.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(endpoints, key = { it.id }) { ep ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .clickable { onSelect(ep) },
                    colors = CardDefaults.cardColors(containerColor = Surface),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("◉  ${ep.name}", style = MaterialTheme.typography.titleMedium)
                        Text("Android · Ready", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
        if (scanning) {
            CircularProgressIndicator()
            Text("Scanning...")
        } else {
            TextButton(onClick = onRescan) { Text("SCAN AGAIN") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
