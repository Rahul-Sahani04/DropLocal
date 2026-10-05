package com.droplocal.app.ui.text

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.droplocal.app.ui.components.*
import com.droplocal.app.ui.transfer.formatBytes
import java.text.NumberFormat

/** Draft, encoded-byte validation and discard confirmation belong to the caller. */
@Composable
fun SendTextScreen(
    text: String,
    onTextChange: (String) -> Unit,
    connected: Boolean,
    onChooseDeviceSend: (String) -> Unit,
    onBack: () -> Unit,
    byteCount: Int,
    byteLimit: Int,
    error: String? = null,
    sending: Boolean = false,
) {
    ScreenColumn {
        ScreenHeading("Send text")
        OutlinedTextField(
            value = text, onValueChange = onTextChange,
            label = { Text("Message") },
            placeholder = { Text("Paste or type text to send") },
            modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12,
            enabled = !sending, isError = error != null || byteCount > byteLimit,
            supportingText = {
                val characters = NumberFormat.getIntegerInstance().format(text.codePointCount(0, text.length))
                Text(if (text.isEmpty()) "$characters characters · ${formatBytes(byteLimit.toLong())} message limit"
                    else "$characters characters · ${formatBytes(byteCount.toLong())} / ${formatBytes(byteLimit.toLong())}")
            },
        )
        if (error != null) StatusText(error, error = true)
        else if (byteCount > byteLimit) StatusText("Message is too large. Shorten it before sending.", error = true)
        if (sending) StatusText("Preparing message…")
        Button(onClick = { onChooseDeviceSend(text) }, modifier = ActionModifier,
            enabled = text.isNotBlank() && byteCount <= byteLimit && error == null && !sending) {
            Text(if (connected) "Send" else "Choose device")
        }
        TextButton(onClick = onBack, modifier = ActionModifier) { Text("Back") }
    }
}
