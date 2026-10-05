package com.droplocal.app.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.droplocal.app.DropLocalApp
import com.droplocal.app.nearby.ConnState
import com.droplocal.app.pairing.QrPayload
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.ui.components.*
import com.droplocal.app.ui.discovery.*
import com.droplocal.app.ui.history.HistoryScreen
import com.droplocal.app.ui.home.HomeScreen
import com.droplocal.app.ui.pairing.PairingScreen
import com.droplocal.app.ui.text.SendTextScreen
import com.droplocal.app.ui.transfer.TransferScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

@Composable
fun NavGraph(app: DropLocalApp, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route ?: "home"
    val endpoints by app.nearby.endpoints.collectAsState()
    val conn by app.nearby.connState.collectAsState()
    val auth by app.nearby.authToken.collectAsState()
    val peer by app.nearby.peerName.collectAsState()
    val incomingFile by app.nearby.incomingFile.collectAsState()
    val incomingText by app.nearby.incomingText.collectAsState()
    val queue by app.transfers.queue.items.collectAsState()
    val history by app.history.history.collectAsState(initial = emptyList())
    val qr by app.nearby.localQr.collectAsState()
    val transportError by app.nearby.error.collectAsState()
    val localError by vm.error.collectAsState()
    val historyError by app.history.error.collectAsState()
    val inspecting by vm.inspecting.collectAsState()
    val draft by vm.draft.collectAsState()
    val displayBatch by vm.displayBatchId.collectAsState()
    val error = localError ?: transportError ?: historyError
    val active = queue.any { !it.status.isTerminal }
    var permissionAction by rememberSaveable { mutableStateOf<String?>(null) }
    var showPermissionHelp by rememberSaveable { mutableStateOf(false) }
    var permissionRevision by remember { mutableIntStateOf(0) }
    var confirmedChallenge by rememberSaveable { mutableStateOf<String?>(null) }
    var leaveAction by rememberSaveable { mutableStateOf<String?>(null) }
    var scannedQrJson by rememberSaveable { mutableStateOf<String?>(null) }
    val challenge = "$peer:$auth"
    val waiting = conn == ConnState.PENDING_AUTH && confirmedChallenge == challenge
    val snackbar = remember { SnackbarHostState() }

    fun navigate(destination: String) { nav.navigate(destination) { launchSingleTop = true } }
    fun home() { nav.navigate("home") { popUpTo("home"); launchSingleTop = true } }
    fun permissionsFor(action: String): List<String> = if (action == "scan") listOf(Manifest.permission.CAMERA)
        else transportPermissions(receiving = action == "advertise" || action == "acceptFile")
    fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            val scanned = QrPayload.parse(contents)
            if (scanned == null) vm.report("This is not a valid active DropLocal receiver QR. Ask the receiver to renew it.")
            else scannedQrJson = scanned.toJson()
        }
    }
    fun perform(action: String) {
        permissionAction = null
        showPermissionHelp = false
        when (action) {
            "discover" -> { app.nearby.startDiscovery(); navigate("discovery") }
            "advertise" -> app.nearby.startAdvertising()
            "acceptFile" -> app.nearby.incomingFile.value?.let {
                vm.showTransfer(it.batchId ?: it.transferId)
                app.nearby.acceptFile(it)
                navigate("transfer")
            }
            "scan" -> {
                if (conn == ConnState.CONNECTED) vm.report("Disconnect before scanning another receiver.")
                else scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR on the receiving phone").setBeepEnabled(false).setOrientationLocked(false))
            }
            "qr" -> {
                val scanned = scannedQrJson?.let { QrPayload.parse(it) }
                scannedQrJson = null
                if (scanned == null) vm.report("The QR expired. Ask the receiver to renew it and scan again.")
                else {
                    app.nearby.requestQrConnection(scanned)
                    if (app.nearby.error.value == null) navigate("discovery")
                }
            }
        }
    }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ++permissionRevision
        val action = permissionAction
        if (action != null && permissionsFor(action).all(::granted)) perform(action)
        else if (action != null) showPermissionHelp = true
    }
    fun ensurePermission(action: String) {
        vm.clearError(); app.nearby.clearError()
        val missing = permissionsFor(action).filterNot(::granted)
        if (missing.isEmpty()) perform(action)
        else {
            permissionAction = action
            val requested = permissionRequestSet(permissionsFor(action), permissionsFor(action).filter(::granted).toSet(), android.os.Build.VERSION.SDK_INT)
            requestPermissions.launch(requested.toTypedArray())
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { try { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { } }
            vm.selectFiles(uris)
        }
    }

    fun acceptConnection() {
        if (conn == ConnState.PENDING_AUTH && auth.isNotBlank() && !waiting) {
            confirmedChallenge = challenge
            app.nearby.acceptConnection()
        }
    }
    fun showActive() {
        app.transfers.queue.active()?.let { vm.showTransfer(it.batchId ?: it.id) }
        navigate("transfer")
    }
    fun finishLeaving(action: String) {
        leaveAction = null
        when (action) {
            "draft" -> { vm.updateDraft(""); vm.cancelPending(); nav.popBackStack() }
            "cancel" -> { vm.cancelAll(); home() }
            "receive" -> { vm.cancelAll(); app.nearby.disconnect(); home() }
        }
    }
    fun back(from: String = route) {
        val liveWork = vm.hasActiveTransfers()
        when (from) {
            "discovery", "pairing" -> { vm.cancelPending(); app.nearby.disconnect(); home() }
            "text" -> if (vm.draft.value.isNotBlank()) leaveAction = "draft" else nav.popBackStack()
            "receive" -> if (liveWork) leaveAction = "receive" else { app.nearby.disconnect(); home() }
            "transfer" -> if (liveWork) leaveAction = "cancel" else home()
            "home" -> if (liveWork) showActive()
            else -> nav.popBackStack()
        }
    }
    val currentPerform by rememberUpdatedState<(String) -> Unit>(::perform)
    val currentEnsure by rememberUpdatedState<(String) -> Unit>(::ensurePermission)
    LaunchedEffect(scannedQrJson) { if (scannedQrJson != null) currentEnsure("qr") }
    LaunchedEffect(Unit) {
        vm.attach(app)
        vm.events.collect { event ->
            when (event) {
                MainViewModel.Event.ChooseDevice -> currentEnsure("discover")
                MainViewModel.Event.ShowTransfers -> navigate("transfer")
            }
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ++permissionRevision
                val action = permissionAction
                if (showPermissionHelp && action != null && permissionsFor(action).all(::granted)) currentPerform(action)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(active, conn == ConnState.ADVERTISING) {
        val window = (context as? Activity)?.window
        if (active || conn == ConnState.ADVERTISING) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(conn, route) {
        if (conn != ConnState.PENDING_AUTH) confirmedChallenge = null
        if (conn in setOf(ConnState.REQUESTED, ConnState.PENDING_AUTH) && route !in setOf("receive", "pairing")) navigate("pairing")
        if (conn == ConnState.CONNECTED && route == "pairing") {
            vm.dispatchPending()
            if (vm.hasActiveTransfers()) navigate("transfer") else home()
        }
    }
    LaunchedEffect(error) {
        if (error != null && snackbar.showSnackbar(error, "Dismiss", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            vm.clearError(); app.nearby.clearError(); app.history.clearError()
        }
    }
    val open: (TransferSession) -> Unit = { session -> scope.launch { openOrShareFile(context, session, false)?.let(vm::report) } }
    val share: (TransferSession) -> Unit = { session -> scope.launch { openOrShareFile(context, session, true)?.let(vm::report) } }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    val status = when (conn) {
                        ConnState.ADVERTISING -> "Visible as ${app.nearby.deviceName}"
                        ConnState.DISCOVERING -> "Scanning nearby devices…"
                        ConnState.CONNECTED -> "Connected to $peer"
                        ConnState.PENDING_AUTH -> "Compare the code with $peer"
                        ConnState.REQUESTED -> "Connecting to $peer…"
                        else -> "Not visible · Not connected"
                    }
                    HomeScreen(status,
                        onSendFile = { if (active) showActive() else pickFiles.launch(arrayOf("*/*")) },
                        onSendText = { if (active) showActive() else navigate("text") },
                        onReceive = { navigate("receive"); if (conn != ConnState.CONNECTED) ensurePermission("advertise") },
                        onHistory = { navigate("history") },
                        onDisconnect = if (conn != ConnState.IDLE) ({ if (active) leaveAction = "receive" else app.nearby.disconnect() }) else null,
                        onScanQr = if (conn == ConnState.CONNECTED) null else ({ ensurePermission("scan") }),
                        onTransfers = if (active) ({ showActive() }) else null,
                    )
                    BackHandler(enabled = active) { back("home") }
                }
                composable("discovery") {
                    DiscoveryScreen(endpoints, conn == ConnState.DISCOVERING,
                        onSelect = { app.nearby.clearError(); app.nearby.requestConnection(it); navigate("pairing") },
                        onRescan = { ensurePermission("discover") }, onBack = { back("discovery") },
                        title = vm.pendingTitle(), selectedSummary = vm.selectedSummary(),
                        error = error, onScanQr = { ensurePermission("scan") },
                    )
                    BackHandler { back("discovery") }
                }
                composable("pairing") {
                    PairingScreen(peer, auth, onConnect = { acceptConnection() }, onCancel = { back("pairing") },
                        canConfirm = conn == ConnState.PENDING_AUTH && auth.isNotBlank(), waiting = waiting, error = localError ?: transportError)
                    BackHandler { back("pairing") }
                }
                composable("text") {
                    SendTextScreen(draft, vm::updateDraft, conn == ConnState.CONNECTED,
                        vm::chooseDeviceForText, onBack = { back("text") },
                        byteCount = app.nearby.encodedTextSize(draft), byteLimit = app.nearby.maxTextBytes,
                        error = error, sending = active)
                    BackHandler { back("text") }
                }
                composable("receive") {
                    ReceiveScreen(conn, peer, auth,
                        onMakeVisible = { ensurePermission("advertise") }, onAccept = { acceptConnection() },
                        onReject = { app.nearby.rejectConnection() }, onStop = { back("receive") }, onBack = { back("receive") },
                        localQr = qr, onRenewQr = { app.nearby.renewQr() },
                        canConfirm = conn == ConnState.PENDING_AUTH && auth.isNotBlank(), waiting = waiting, error = localError ?: transportError)
                    BackHandler { back("receive") }
                }
                composable("transfer") {
                    val sessions = if (displayBatch != null) queue.filter { it.batchId == displayBatch || it.id == displayBatch }
                        else app.transfers.queue.latestBatch()
                    TransferScreen(sessions, peer, onDone = { home() },
                        onRetry = { if (!app.nearby.retryTransfer(it)) vm.report("Retry expired. Choose the file again.") },
                        onCancelAll = { vm.cancelAll() },
                        canRetry = { app.nearby.canRetryTransfer(it.id) }, onOpen = open, onShare = share, error = error)
                    BackHandler { back("transfer") }
                }
                composable("history") {
                    HistoryScreen(history, vm::clearHistory, onBack = { back("history") }, error = historyError, onOpen = open, onShare = share)
                    BackHandler { back("history") }
                }
            }
        }
    }

    incomingFile?.let { info ->
        if (!showPermissionHelp) IncomingFileDialog(info,
            onAccept = { ensurePermission("acceptFile") }, onReject = { app.nearby.rejectFile(info) })
    }
    incomingText?.takeIf { incomingFile == null }?.let { info ->
        IncomingTextDialog(info,
            onCopy = { text ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Received text", text))
                app.nearby.dismissText()
                scope.launch { snackbar.showSnackbar("Text copied") }
            },
            onDismiss = { app.nearby.dismissText() },
            onShare = { shareText(context, it)?.let(vm::report) })
    }
    if (inspecting) AlertDialog(
        onDismissRequest = { vm.cancelPending() }, title = { Text("Preparing your selection") },
        text = { Column { CircularProgressIndicator(); Text("Reading file names and sizes…") } },
        confirmButton = { TextButton(onClick = { vm.cancelPending() }) { Text("Cancel") } })
    if (leaveAction != null) AlertDialog(
        onDismissRequest = { leaveAction = null },
        title = { Text(if (leaveAction == "draft") "Leave unsent text?" else "Stop active transfers?") },
        text = { Text(if (leaveAction == "draft") "You can keep your draft or discard it. Nothing will be sent."
            else "Queued and active transfers will be cancelled. Files already saved are kept.") },
        confirmButton = {
            Column {
                if (leaveAction == "draft") TextButton(onClick = {
                    leaveAction = null; vm.cancelPending(); nav.popBackStack()
                }) { Text("Keep draft and leave") }
                TextButton(onClick = { leaveAction?.let(::finishLeaving) }) {
                    Text(if (leaveAction == "draft") "Discard draft" else "Cancel transfers and leave")
                }
            }
        },
        dismissButton = { TextButton(onClick = { leaveAction = null }) { Text("Stay here") } })
    if (showPermissionHelp && permissionAction != null) {
        val action = permissionAction!!
        val readiness = permissionRevision.let { permissionsFor(action).groupBy(::permissionLabel) }
        AlertDialog(onDismissRequest = { showPermissionHelp = false; permissionAction = null; scannedQrJson = null },
            title = { Text(if (action == "scan") "Camera access needed" else "Nearby access needed") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(if (action == "scan") "Camera is used only to scan the receiver's QR. You can choose a device without camera access."
                        else "DropLocal uses local radios to connect, not a cloud upload. Grant the required access or open app settings if it was permanently denied.")
                    readiness.forEach { (label, permissions) -> Text("$label · ${if (permissions.all(::granted)) "Ready" else "Not granted"}") }
                }
            },
            confirmButton = { TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text("Open app settings") } },
            dismissButton = { TextButton(onClick = { ensurePermission(action) }) { Text("Try again") } })
    }
}
