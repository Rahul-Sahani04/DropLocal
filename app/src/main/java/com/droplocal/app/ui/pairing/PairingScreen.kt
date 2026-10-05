package com.droplocal.app.ui.pairing

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.droplocal.app.ui.components.*

@Composable
fun PairingScreen(
    peer: String,
    code: String,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    canConfirm: Boolean,
    waiting: Boolean = false,
    error: String? = null,
) {
    ScreenColumn {
        ScreenHeading("Verify connection")
        Text(peer.ifBlank { "Nearby device" }, style = MaterialTheme.typography.titleLarge)
        if (code.isNotBlank()) {
            Text("Compare these digits on both phones")
            Text(code, style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { contentDescription = "Connection code ${code.filterNot { it.isWhitespace() }.toList().joinToString(" ")}" })
            Text("Only confirm if the codes match. A QR code finds a receiver; it never replaces this check.")
        } else StatusText("Waiting for the Nearby verification code…")
        if (waiting) StatusText("Approved on this phone. Waiting for the other phone…")
        if (error != null) StatusText(error, error = true)
        Button(onClick = onConnect, modifier = ActionModifier,
            enabled = canConfirm && code.isNotBlank() && !waiting && error == null) { Text("Codes match") }
        OutlinedButton(onClick = onCancel, modifier = ActionModifier) { Text("Cancel connection") }
    }
}
