package com.droplocal.app.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SendTextScreen(
    connected: Boolean,
    onChooseDeviceSend: (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("SEND TEXT", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("Paste something to send...") },
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        Text("${text.length} characters")
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onChooseDeviceSend(text) },
            enabled = text.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (connected) "SEND NOW" else "CHOOSE DEVICE") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
