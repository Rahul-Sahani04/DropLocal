package com.droplocal.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.droplocal.app.DropLocalApp
import com.droplocal.app.nearby.ConnState
import com.droplocal.app.ui.components.IncomingFileDialog
import com.droplocal.app.ui.components.IncomingTextDialog
import com.droplocal.app.ui.discovery.DiscoveryScreen
import com.droplocal.app.ui.discovery.ReceiveScreen
import com.droplocal.app.ui.history.HistoryScreen
import com.droplocal.app.ui.home.HomeScreen
import com.droplocal.app.ui.pairing.PairingScreen
import com.droplocal.app.ui.text.SendTextScreen
import com.droplocal.app.ui.transfer.TransferScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun NavGraph(app: DropLocalApp, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.attach(app) }
    val nav = rememberNavController()
    val endpoints by app.nearby.endpoints.collectAsState()
    val conn by app.nearby.connState.collectAsState()
    val auth by app.nearby.authToken.collectAsState()
    val peer by app.nearby.peerName.collectAsState()
    val incomingFile by app.nearby.incomingFile.collectAsState()
    val incomingText by app.nearby.incomingText.collectAsState()
    val queue by app.transfers.queue.items.collectAsState()
    val history by app.history.history.collectAsState(initial = emptyList())
    val qr by vm.qr.collectAsState()
    var pendingText by remember { mutableStateOf<String?>(null) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            vm.addPicked(uris)
            vm.sendPickedAsQueue()
            nav.navigate("transfer")
        }
    }

    incomingFile?.let { info ->
        IncomingFileDialog(
            info,
            onAccept = {
                vm.acceptIncoming(info)
                nav.navigate("transfer")
            },
            onReject = { app.nearby.dismissIncoming() },
        )
    }
    incomingText?.let { info ->
        IncomingTextDialog(
            info,
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("droplocal", it))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                app.nearby.dismissIncoming()
            },
            onDismiss = { app.nearby.dismissIncoming() },
        )
    }

    NavHost(nav, startDestination = "home") {
        composable("home") {
            val status = when (conn) {
                ConnState.ADVERTISING -> "Discoverable as ${app.nearby.deviceName}"
                ConnState.DISCOVERING -> "Scanning…"
                ConnState.CONNECTED -> "Connected to $peer"
                ConnState.PENDING_AUTH -> "Confirm code $auth"
                else -> "Not discoverable"
            }
            HomeScreen(
                discoverable = status,
                onSendFile = {
                    app.nearby.startDiscovery()
                    nav.navigate("discovery")
                },
                onSendText = { nav.navigate("text") },
                onReceive = { nav.navigate("receive") },
                onHistory = { nav.navigate("history") },
            )
        }
        composable("discovery") {
            DiscoveryScreen(
                endpoints = endpoints,
                scanning = conn == ConnState.DISCOVERING,
                onSelect = {
                    app.nearby.requestConnection(it)
                    nav.navigate("pairing")
                },
                onRescan = { app.nearby.startDiscovery() },
                onBack = { app.nearby.stopAll(); nav.popBackStack() },
            )
        }
        composable("pairing") {
            LaunchedEffect(Unit) { vm.renewQr() }
            PairingScreen(
                peer = peer.ifEmpty { endpoints.firstOrNull()?.name ?: "" },
                code = auth,
                qrJson = qr.toJson(),
                onConnect = {
                    app.nearby.acceptConnection()
                    // After connect, launch file picker (sender flow)
                    pickFiles.launch(arrayOf("*/*"))
                },
                onCancel = { app.nearby.rejectConnection(); nav.popBackStack() },
            )
            LaunchedEffect(conn) {
                if (conn == ConnState.CONNECTED && pendingText != null) {
                    app.nearby.sendText(pendingText!!)
                    pendingText = null
                    nav.navigate("transfer")
                }
            }
        }
        composable("text") {
            SendTextScreen(
                connected = conn == ConnState.CONNECTED,
                onChooseDeviceSend = { t ->
                    if (conn == ConnState.CONNECTED) {
                        vm.sendText(t)
                        nav.navigate("transfer")
                    } else {
                        pendingText = t
                        app.nearby.startDiscovery()
                        nav.navigate("discovery")
                    }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("receive") {
            ReceiveScreen(
                state = conn,
                peer = peer,
                authCode = auth,
                onMakeVisible = { app.nearby.startAdvertising() },
                onAccept = { app.nearby.acceptConnection() },
                onReject = { app.nearby.rejectConnection() },
                onStop = { app.nearby.disconnect() },
                onBack = { app.nearby.stopAll(); nav.popBackStack() },
            )
        }
        composable("transfer") {
            val active = queue.lastOrNull()
            TransferScreen(
                session = active,
                peer = active?.peer?.ifEmpty { peer } ?: peer,
                onDone = {
                    vm.clearPicked()
                    nav.navigate("home") { popUpTo("home") { inclusive = false } }
                },
                onRetry = {
                    active?.let {
                        app.transfers.retry(it.id)
                        // Best-effort re-send for text; files need re-pick in MVP
                        Toast.makeText(context, "Re-queued — resend from picker if file", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = {
                    active?.let { app.transfers.cancel(it.id) }
                    app.nearby.disconnect()
                    nav.popBackStack()
                },
            )
        }
        composable("history") {
            HistoryScreen(
                items = history,
                onClear = { CoroutineScope(Dispatchers.IO).launch { app.history.clear() } },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
