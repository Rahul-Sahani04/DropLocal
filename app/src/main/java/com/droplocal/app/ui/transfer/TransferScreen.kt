package com.droplocal.app.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droplocal.app.transfer.TransferSession
import java.util.Locale

fun fmtMB(b: Long): String = String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
fun fmtSpeed(bps: Double): String = String.format(Locale.US, "%.1f MB/s", bps / 1_048_576.0)

@Composable
fun TransferScreen(
    session: TransferSession?,
    peer: String,
    onDone: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (session == null) {
            Text("No active transfer")
            OutlinedButton(onClick = onDone) { Text("Back") }
            return
        }
        Text(if (session.direction.name == "SENT") "SENDING TO" else "RECEIVING FROM")
        Text(peer, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(session.name, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = session.progress,
            modifier = Modifier.fillMaxWidth().height(10.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text("${(session.progress * 100).toInt()}%")
        Text("${fmtMB(session.bytes)} / ${fmtMB(session.size)}")
        Text(fmtSpeed(session.speedBps))
        val eta = if (session.speedBps > 0)
            "%.1f sec remaining".format((session.size - session.bytes) / session.speedBps)
        else "…"
        Text(eta)
        Spacer(Modifier.height(8.dp))
        Text("Saving to Downloads", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        when (session.status.name) {
            "DONE" -> {
                Text("✓ TRANSFER COMPLETE", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("%.1fs TRANSFER TIME".format(session.elapsedSec))
                Text("Local transfer · No cloud upload")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDone) { Text("SEND ANOTHER / HOME") }
            }
            "FAILED" -> {
                Text("TRANSFER INTERRUPTED: ${session.error ?: ""}")
                Button(onClick = onRetry) { Text("RETRY") }
                OutlinedButton(onClick = onCancel) { Text("CANCEL") }
            }
            else -> {
                OutlinedButton(onClick = onCancel) { Text("CANCEL") }
            }
        }
    }
}
