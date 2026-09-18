package com.droplocal.app.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.nearby.ConnState

@Composable
fun ReceiveScreen(
    state: ConnState,
    peer: String,
    authCode: String,
    onMakeVisible: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("RECEIVE", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        when (state) {
            ConnState.ADVERTISING -> Text("This device is visible as nearby. Keep the app open.")
            ConnState.PENDING_AUTH -> {
                Text("Connection from $peer")
                Text("Code: $authCode", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAccept) { Text("ACCEPT") }
                OutlinedButton(onClick = onReject) { Text("REJECT") }
            }
            ConnState.CONNECTED -> {
                Text("Connected to $peer — waiting for transfer…")
                OutlinedButton(onClick = onStop) { Text("STOP") }
            }
            else -> {
                Button(onClick = onMakeVisible) { Text("MAKE THIS DEVICE VISIBLE") }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}
