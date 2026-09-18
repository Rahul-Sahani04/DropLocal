package com.droplocal.app.ui.history

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.ui.theme.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    items: List<TransferSession>,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    val fmt = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US)
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Text("Last 10 local transfers")
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.id }) { s ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${if (s.status.name == "DONE") "✓" else "•"} ${s.name}")
                        Text(
                            "${s.direction.name.lowercase().replaceFirstChar { it.uppercase() }} " +
                                "${if (s.peer.isNotEmpty()) "· ${s.peer}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "%.1f MB · %.1fs · %s".format(
                                s.size / 1_048_576.0, s.elapsedSec,
                                fmt.format(Date(s.startedAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onClear) { Text("Clear history") }
        TextButton(onClick = onBack) { Text("Back") }
        Spacer(Modifier.height(4.dp))
    }
}
