package com.droplocal.app.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droplocal.app.ui.QrImage

@Composable
fun PairingScreen(
    peer: String,
    code: String,
    qrJson: String,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CONNECTING TO", style = MaterialTheme.typography.labelLarge)
        Text(peer.ifEmpty { "…" }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("COMPARE THIS CODE", style = MaterialTheme.typography.labelLarge)
        Text(
            code.ifEmpty { "··· ···" },
            fontSize = 40.sp, fontWeight = FontWeight.Bold,
        )
        Text("Does the code match on both devices?")
        Spacer(Modifier.height(12.dp))
        QrImage(qrJson, Modifier.size(180.dp))
        Text("Or scan — session expires in 02:00", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = onConnect) { Text("CONNECT") }
            Spacer(Modifier.padding(8.dp))
            OutlinedButton(onClick = onCancel) { Text("CANCEL") }
        }
    }
}
