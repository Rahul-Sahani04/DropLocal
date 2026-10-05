package com.droplocal.app.ui.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.nearby.ConnState
import com.droplocal.app.pairing.QrPayload
import com.droplocal.app.ui.QrImage
import com.droplocal.app.ui.components.*
import kotlinx.coroutines.delay

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
    localQr: QrPayload? = null,
    onRenewQr: (() -> Unit)? = null,
    canConfirm: Boolean = false,
    waiting: Boolean = false,
    error: String? = null,
) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(localQr?.expires, state) {
        nowSec = System.currentTimeMillis() / 1000
        while (state == ConnState.ADVERTISING && localQr != null && nowSec < localQr.expires) {
            delay(1000)
            nowSec = System.currentTimeMillis() / 1000
        }
    }
    ScreenColumn {
        ScreenHeading("Receive")
        if (error != null) StatusText(error, error = true)
        when (state) {
            ConnState.ADVERTISING -> {
                StatusText("Visible to nearby devices. Keep this screen open.")
                Text("Senders can discover this phone or scan this receiver QR. Both phones must still compare Nearby codes.")
                if (localQr != null && !localQr.isExpired(nowSec)) {
                    QrImage(localQr.toJson(), Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp).aspectRatio(1f))
                    val remaining = (localQr.expires - nowSec).coerceAtLeast(0)
                    Text("QR expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}")
                } else Text("QR unavailable or expired. Nearby discovery still works.")
                if (onRenewQr != null) OutlinedButton(onClick = onRenewQr, modifier = ActionModifier) { Text("Renew QR") }
                OutlinedButton(onClick = onStop, modifier = ActionModifier) { Text("Stop visibility") }
            }
            ConnState.PENDING_AUTH -> {
                Text("Connection from ${peer.ifBlank { "Nearby device" }}")
                Text("Compare the code on both phones")
                if (authCode.isNotBlank()) ScreenHeading(authCode)
                else StatusText("Waiting for the Nearby verification code…")
                if (waiting) StatusText("Approved here. Waiting for the other phone…")
                Button(onClick = onAccept, modifier = ActionModifier,
                    enabled = canConfirm && authCode.isNotBlank() && !waiting && error == null) { Text("Codes match") }
                OutlinedButton(onClick = onReject, modifier = ActionModifier) { Text("Reject connection") }
            }
            ConnState.CONNECTED -> {
                StatusText("Connected to ${peer.ifBlank { "Nearby device" }}. Waiting for a transfer offer.")
                Text("You approve each incoming file before it is sent.")
                OutlinedButton(onClick = onStop, modifier = ActionModifier) { Text("Disconnect") }
            }
            ConnState.REQUESTED -> StatusText("Connecting…")
            else -> {
                Text("Make this phone visible to receive files or text. Keep Wi-Fi and Bluetooth on.")
                Button(onClick = onMakeVisible, modifier = ActionModifier) { Text("Make visible") }
            }
        }
        TextButton(onClick = onBack, modifier = ActionModifier) { Text("Back") }
    }
}
