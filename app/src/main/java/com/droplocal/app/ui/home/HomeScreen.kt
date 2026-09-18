package com.droplocal.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droplocal.app.ui.theme.Muted

@Composable
fun HomeScreen(
    discoverable: String,
    onSendFile: () -> Unit,
    onSendText: () -> Unit,
    onReceive: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("DROP", fontSize = 52.sp, fontWeight = FontWeight.Black)
        Text("LOCAL", fontSize = 52.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(4.dp))
        Text("Nearby. Private. Direct.", color = Muted)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onSendFile, modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("SEND FILE") }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onSendText, modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
        ) { Text("SEND TEXT") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("RECEIVE — MAKE THIS DEVICE VISIBLE")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onHistory) { Text("History") }
        Spacer(Modifier.height(16.dp))
        Text(discoverable, color = Muted)
    }
}
