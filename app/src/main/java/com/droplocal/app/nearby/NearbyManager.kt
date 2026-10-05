package com.droplocal.app.nearby

import android.content.Context
import android.net.Uri
import com.droplocal.app.BuildConfig
import com.droplocal.app.pairing.QrPayload
import com.droplocal.app.storage.FileRepository
import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferRepository
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NearbyEndpoint(val id: String, val name: String, val session: String? = null, val expires: Long? = null)
data class IncomingFile(
    val transferId: String, val name: String, val size: Long,
    val mime: String, val sender: String, val payloadId: Long,
    val batchId: String? = null, val batchIndex: Int = 1, val batchCount: Int = 1, val batchSize: Long = -1,
)
data class IncomingText(val text: String, val sender: String)
enum class ConnState { IDLE, ADVERTISING, DISCOVERING, REQUESTED, PENDING_AUTH, CONNECTED }

/** Application-owned transport. Discovery visibility and authenticated connection truth are separate. */
class NearbyManager(
    private val context: Context,
    private val transfers: TransferRepository,
    private val files: FileRepository = FileRepository(context),
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val serviceId: String = BuildConfig.APPLICATION_ID
    val deviceName: String = android.os.Build.MODEL ?: "Android"
    private val _endpoints = MutableStateFlow<List<NearbyEndpoint>>(emptyList())
    val endpoints: StateFlow<List<NearbyEndpoint>> = _endpoints
    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState
    private val _authToken = MutableStateFlow("")
    val authToken: StateFlow<String> = _authToken
    private val _peerName = MutableStateFlow("")
    val peerName: StateFlow<String> = _peerName
    private val _incomingFile = MutableStateFlow<IncomingFile?>(null)
    val incomingFile: StateFlow<IncomingFile?> = _incomingFile
    private val _incomingText = MutableStateFlow<IncomingText?>(null)
    val incomingText: StateFlow<IncomingText?> = _incomingText
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _localQr = MutableStateFlow(QrPayload(session = UUID.randomUUID().toString(), device = deviceName))
    val localQr: StateFlow<QrPayload> = _localQr

    private var generation = 0L
    private var discoveryEpoch = 0L
    private var advertisingEpoch = 0L
    private var advertising = false
    private var discovering = false
    private var startingAdvertisement = false
    private var startingDiscovery = false
    private var pendingEndpoint: String? = null
    private var requestedEndpoint: String? = null
    private var connectedEndpoint: String? = null
    private var locallyAccepted = false
    private var qrTarget: QrPayload? = null
    private var connectionTimeout: Job? = null
    private var discoveryTimeout: Job? = null
    private var advertisingTimeout: Job? = null
    private var qrTimeout: Job? = null
    private val endpointNames = mutableMapOf<String, String>()
    private val advertisements = mutableMapOf<String, EndpointAdvertisement.Identity>()

    private data class Source(val name: String, val size: Long, val mime: String, val file: File)
    private class Runtime(
        val state: TransferAttempt,
        val sessionId: String,
        val info: IncomingFile,
        val outgoingPayload: Payload? = null,
    ) {
        var receivedPayload: Payload? = null
        var total: Long? = null
        var savedSize: Long? = null
        var timeout: Job? = null
        var saveJob: Job? = null
    }
    private data class TextDelivery(val sessionId: String, val generation: Long, var timeout: Job? = null)
    private data class ReceivedText(val id: String, val text: String, val sender: String)
    private data class ControlDelivery(val wireId: String, val generation: Long)
    private val attempts = TransferAttemptIndex<Runtime> { it.state }
    private val sources = mutableMapOf<String, Source>()
    private val sendQueue = SingleActiveQueue<String>()
    private val receiveQueue = SingleActiveQueue<String>()
    private val textDeliveries = mutableMapOf<Long, TextDelivery>()
    private val receivedTexts = mutableMapOf<Long, ReceivedText>()
    private val pendingTextDialogs = ArrayDeque<IncomingText>()
    private val controls = mutableMapOf<Long, ControlDelivery>()
    private val failedSources = linkedMapOf<String, Job>()
    private val seenIds = mutableSetOf<String>()
    private val seenPayloadIds = mutableSetOf<Long>()
    private var settlingAll = false

    fun clearError() { _error.value = null }
    val maxTextBytes: Int get() = ConnectionsClient.MAX_BYTES_DATA_SIZE
    fun encodedTextSize(text: String): Int = TransferProtocol.encode(
        TransferProtocol.Frame("text", "00000000-0000-4000-8000-000000000000", text = text),
    ).size
    private fun report(message: String) { _error.value = message }
    private fun nowSec() = System.currentTimeMillis() / 1000
    private fun authenticated(endpoint: String, token: Long) =
        token == generation && endpoint == connectedEndpoint && _connState.value == ConnState.CONNECTED
    private fun refreshState() {
        _connState.value = when {
            connectedEndpoint != null -> ConnState.CONNECTED
            pendingEndpoint != null -> ConnState.PENDING_AUTH
            requestedEndpoint != null -> ConnState.REQUESTED
            advertising -> ConnState.ADVERTISING
            discovering -> ConnState.DISCOVERING
            else -> ConnState.IDLE
        }
    }
    private fun advertisement(): String = _localQr.value.let {
        EndpointAdvertisement.encode(it.session, it.expires, deviceName)
    }

    private fun lifecycle(token: Long) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (token != generation) return
            if (connectedEndpoint != null ||
                (pendingEndpoint != null && pendingEndpoint != endpointId) ||
                (requestedEndpoint != null && requestedEndpoint != endpointId) ||
                (!advertising && requestedEndpoint != endpointId)) {
                rejectEndpoint(endpointId)
                return
            }
            val identity = EndpointAdvertisement.decode(info.endpointName)
            val target = qrTarget
            if (target != null && !EndpointAdvertisement.matches(identity, target.session, target.expires, nowSec())) {
                rejectEndpoint(endpointId)
                resetConnection("The QR session no longer matches this device.")
                return
            }
            pendingEndpoint = endpointId
            locallyAccepted = false
            endpointNames[endpointId] = identity?.name ?: info.endpointName.take(80)
            _peerName.value = endpointNames.getValue(endpointId)
            _authToken.value = info.authenticationDigits
            refreshState()
            armConnectionTimeout(token)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (token != generation || endpointId != pendingEndpoint) return
            if (!result.status.isSuccess || !locallyAccepted) {
                client.disconnectFromEndpoint(endpointId)
                resetConnection("Connection was rejected or authentication failed.")
                return
            }
            connectedEndpoint = endpointId
            pendingEndpoint = null
            requestedEndpoint = null
            qrTarget = null
            connectionTimeout?.cancel()
            qrTimeout?.cancel()
            _authToken.value = ""
            stopAll()
            refreshState()
        }
        override fun onDisconnected(endpointId: String) {
            if (token != generation) return
            if (endpointId == connectedEndpoint || endpointId == pendingEndpoint || endpointId == requestedEndpoint) {
                resetConnection("The other device disconnected.")
            }
        }
    }

    fun startAdvertising() {
        if (startingAdvertisement || connectedEndpoint != null || pendingEndpoint != null || requestedEndpoint != null) return
        stopAll()
        startingAdvertisement = true
        clearError()
        if (_localQr.value.expires <= nowSec()) renewQr()
        val epoch = ++advertisingEpoch
        val token = generation
        advertising = false
        client.stopAdvertising()
        advertisingTimeout?.cancel()
        advertisingTimeout = scope.launch {
            delay(30_000)
            if (token == generation && epoch == advertisingEpoch && !advertising) {
                ++advertisingEpoch; startingAdvertisement = false; client.stopAdvertising(); refreshState()
                report("Making this device visible timed out. Check permissions and radios.")
            }
        }
        try {
            client.startAdvertising(advertisement(), serviceId, lifecycle(token),
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnSuccessListener {
                    if (token == generation && epoch == advertisingEpoch) {
                        advertisingTimeout?.cancel(); startingAdvertisement = false; advertising = true; refreshState()
                    }
                }.addOnFailureListener {
                    if (token == generation && epoch == advertisingEpoch) {
                        advertisingTimeout?.cancel()
                        startingAdvertisement = false; advertising = false; refreshState(); report("Cannot make this device visible: ${it.message ?: "check permissions and radios"}")
                    }
                }
        } catch (e: Exception) { startingAdvertisement = false; advertisingTimeout?.cancel(); report("Cannot advertise: ${e.message}"); refreshState() }
    }

    fun startDiscovery() {
        if (startingDiscovery || connectedEndpoint != null || pendingEndpoint != null || requestedEndpoint != null) return
        startingDiscovery = true
        ++advertisingEpoch; startingAdvertisement = false; advertising = false
        client.stopAdvertising(); advertisingTimeout?.cancel()
        clearError()
        client.stopDiscovery()
        val epoch = ++discoveryEpoch
        val token = generation
        discovering = false
        _endpoints.value = emptyList()
        endpointNames.clear()
        advertisements.clear()
        val callback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (token != generation || epoch != discoveryEpoch) return
                val identity = EndpointAdvertisement.decode(info.endpointName) ?: return
                endpointNames[endpointId] = identity.name
                advertisements[endpointId] = identity
                val endpoint = NearbyEndpoint(endpointId, identity.name, identity.session, identity.expires)
                _endpoints.value = _endpoints.value.filterNot { it.id == endpointId } + endpoint
                val target = qrTarget
                if (target != null && EndpointAdvertisement.matches(identity, target.session, target.expires, nowSec())) {
                    requestConnection(endpoint)
                }
            }
            override fun onEndpointLost(endpointId: String) {
                if (token != generation || epoch != discoveryEpoch) return
                _endpoints.value = _endpoints.value.filterNot { it.id == endpointId }
                advertisements.remove(endpointId)
                endpointNames.remove(endpointId)
            }
        }
        try {
            client.startDiscovery(serviceId, callback,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build())
                .addOnSuccessListener {
                    if (token == generation && epoch == discoveryEpoch) { startingDiscovery = false; discovering = true; refreshState() }
                }.addOnFailureListener {
                    if (token == generation && epoch == discoveryEpoch) {
                        startingDiscovery = false; discovering = false; refreshState(); report("Cannot scan: ${it.message ?: "check permissions and radios"}")
                    }
                }
        } catch (e: Exception) { startingDiscovery = false; report("Cannot scan: ${e.message}"); refreshState() }
        discoveryTimeout?.cancel()
        discoveryTimeout = scope.launch {
            delay(60_000)
            if (token == generation && epoch == discoveryEpoch) {
                client.stopDiscovery(); ++discoveryEpoch; startingDiscovery = false; discovering = false
                qrTarget = null; refreshState(); report("Discovery timed out. Check the other device is visible and try again.")
            }
        }
    }

    /** Stops radio visibility only. It never invents an IDLE state for a live connection. */
    fun stopAll() {
        ++advertisingEpoch; ++discoveryEpoch
        advertising = false; discovering = false
        startingAdvertisement = false; startingDiscovery = false
        client.stopAdvertising(); client.stopDiscovery()
        discoveryTimeout?.cancel(); advertisingTimeout?.cancel()
        if (requestedEndpoint == null && pendingEndpoint == null) { qrTimeout?.cancel(); qrTarget = null }
        refreshState()
    }

    fun renewQr() {
        _localQr.value = QrPayload(session = UUID.randomUUID().toString(), device = deviceName)
        if (advertising) startAdvertising()
    }

    fun requestQrConnection(qr: QrPayload) {
        if (connectedEndpoint != null || pendingEndpoint != null || requestedEndpoint != null) {
            report("Disconnect before connecting to another device."); return
        }
        if (qr.v != 1 || qr.app != "droplocal" || !TransferProtocol.validId(qr.session) ||
            qr.expires <= nowSec() || qr.expires > nowSec() + 120) {
            report("This QR code is invalid or expired."); return
        }
        qrTarget = qr
        qrTimeout?.cancel()
        val token = generation
        qrTimeout = scope.launch {
            delay(((qr.expires - nowSec()) * 1000).coerceAtLeast(1))
            if (token == generation && qrTarget == qr) {
                if (requestedEndpoint != null || pendingEndpoint != null) resetConnection("The QR session expired before authentication.")
                else { stopAll(); report("The QR session expired. Scan a new code.") }
            }
        }
        val found = _endpoints.value.firstOrNull {
            EndpointAdvertisement.matches(advertisements[it.id], qr.session, qr.expires, nowSec())
        }
        if (found != null) requestConnection(found)
        else startDiscovery()
    }

    fun requestConnection(endpoint: NearbyEndpoint) {
        if (connectedEndpoint != null || pendingEndpoint != null || requestedEndpoint != null) return
        val target = qrTarget
        if (target != null && !EndpointAdvertisement.matches(advertisements[endpoint.id], target.session, target.expires, nowSec())) {
            report("The discovered device does not match this QR session."); return
        }
        client.stopDiscovery(); client.stopAdvertising()
        ++discoveryEpoch; ++advertisingEpoch
        discovering = false; advertising = false
        discoveryTimeout?.cancel(); advertisingTimeout?.cancel()
        requestedEndpoint = endpoint.id
        _peerName.value = endpoint.name
        _authToken.value = ""
        locallyAccepted = false
        val token = generation
        refreshState()
        armConnectionTimeout(token)
        try {
            client.requestConnection(advertisement(), endpoint.id, lifecycle(token)).addOnFailureListener {
                if (token == generation && requestedEndpoint == endpoint.id) resetConnection("Cannot connect: ${it.message}")
            }
        } catch (e: Exception) { resetConnection("Cannot connect: ${e.message}") }
    }

    fun acceptConnection() {
        val endpoint = pendingEndpoint ?: return
        if (_authToken.value.isBlank() || locallyAccepted) return
        val token = generation
        locallyAccepted = true
        try {
            client.acceptConnection(endpoint, payloadCallback(token)).addOnFailureListener {
                if (token == generation && pendingEndpoint == endpoint) resetConnection("Cannot authenticate: ${it.message}")
            }
        } catch (e: Exception) { resetConnection("Cannot authenticate: ${e.message}") }
    }

    fun rejectConnection() {
        pendingEndpoint?.let(::rejectEndpoint)
        resetConnection(null)
    }

    private fun rejectEndpoint(endpoint: String) {
        val token = generation
        try {
            client.rejectConnection(endpoint).addOnFailureListener {
                if (token == generation) {
                    client.disconnectFromEndpoint(endpoint)
                    report("Could not reject the connection cleanly: ${it.message}")
                }
            }
        } catch (e: Exception) {
            client.disconnectFromEndpoint(endpoint)
            report("Could not reject the connection cleanly: ${e.message}")
        }
    }

    private fun armConnectionTimeout(token: Long) {
        connectionTimeout?.cancel()
        connectionTimeout = scope.launch {
            delay(120_000)
            if (token == generation && connectedEndpoint == null) resetConnection("Connection/authentication timed out.")
        }
    }

    fun disconnect() { resetConnection(null) }

    private fun resetConnection(message: String?) {
        val endpoints = listOfNotNull(connectedEndpoint, pendingEndpoint, requestedEndpoint).distinct()
        ++generation // Invalidate callbacks before touching Nearby or settling sessions.
        connectedEndpoint = null; pendingEndpoint = null; requestedEndpoint = null
        locallyAccepted = false; qrTarget = null
        connectionTimeout?.cancel(); qrTimeout?.cancel()
        stopAll()
        settleAll(message ?: "Disconnected", cancelled = message == null)
        client.stopAllEndpoints()
        endpoints.forEach { client.disconnectFromEndpoint(it) }
        _authToken.value = ""; _peerName.value = ""
        _endpoints.value = emptyList(); endpointNames.clear(); advertisements.clear()
        _incomingFile.value = null // Completed received text stays available until explicitly dismissed.
        seenIds.clear(); seenPayloadIds.clear(); controls.clear(); receivedTexts.clear()
        _localQr.value = QrPayload(session = UUID.randomUUID().toString(), device = deviceName)
        refreshState()
        if (message != null) report(message)
    }

    private fun payloadCallback(token: Long) = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (token != generation) {
                if (payload.type == Payload.Type.FILE) cleanReceived(payload) else payload.close()
                return
            }
            if (!authenticated(endpointId, token)) { rejectPayload(payload); return }
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val frame = payload.asBytes()?.let { TransferProtocol.decode(it, ConnectionsClient.MAX_BYTES_DATA_SIZE) }
                    if (frame == null) { report("Received an invalid or unsupported transfer message."); return }
                    if (frame.type == "text") receiveText(payload.id, frame)
                    else receiveControl(frame)
                }
                Payload.Type.FILE -> {
                    val runtime = attempts.payload(payload.id, token)
                    if (runtime == null || runtime.state.outgoing ||
                        runtime.state.phase !in setOf(TransferAttempt.Phase.ACCEPTED, TransferAttempt.Phase.TRANSFERRING, TransferAttempt.Phase.SAVING) ||
                        runtime.receivedPayload != null) {
                        rejectPayload(payload)
                        if (runtime != null && !runtime.state.outgoing) fail(runtime, "File arrived without consent or was duplicated.")
                        else report("Rejected an unsolicited file payload.")
                        return
                    }
                    if (receivedUri(payload) == null) {
                        rejectPayload(payload); fail(runtime, "The received file has no readable URI."); return
                    }
                    runtime.receivedPayload = payload
                    runtime.state.start()
                    transfers.running(runtime.sessionId)
                    maybeSave(runtime)
                }
                else -> rejectPayload(payload)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (!authenticated(endpointId, token)) return
            controls[update.payloadId]?.let { delivery ->
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> controls.remove(update.payloadId)
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        controls.remove(update.payloadId)
                        if (delivery.generation == generation) attempts[delivery.wireId]?.let {
                            fail(it, "Could not deliver the transfer decision/message.")
                        }
                    }
                }
                return
            }
            textDeliveries[update.payloadId]?.let { delivery ->
                if (delivery.generation != generation) return
                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> transfers.progress(delivery.sessionId, update.bytesTransferred, update.totalBytes)
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        textDeliveries.remove(update.payloadId); delivery.timeout?.cancel()
                        transfers.progress(delivery.sessionId, update.bytesTransferred, update.totalBytes)
                        transfers.complete(delivery.sessionId)
                    }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        textDeliveries.remove(update.payloadId); delivery.timeout?.cancel()
                        if (update.status == PayloadTransferUpdate.Status.CANCELED) transfers.cancel(delivery.sessionId)
                        else { transfers.fail(delivery.sessionId, "Text delivery failed."); report("Text delivery failed.") }
                    }
                }
                return
            }
            receivedTexts[update.payloadId]?.let { text ->
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        receivedTexts.remove(update.payloadId)
                        val incoming = IncomingText(text.text, text.sender)
                        if (_incomingText.value == null) _incomingText.value = incoming else pendingTextDialogs.addLast(incoming)
                        transfers.progress(text.id, text.text.toByteArray(Charsets.UTF_8).size.toLong())
                        transfers.complete(text.id)
                    }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        receivedTexts.remove(update.payloadId)
                        transfers.fail(text.id, "Text receive failed.")
                    }
                }
                return
            }
            val runtime = attempts.payload(update.payloadId, token) ?: return
            if (runtime.state.generation != token || runtime.state.phase == TransferAttempt.Phase.TERMINAL) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (runtime.state.phase != TransferAttempt.Phase.TRANSFERRING && runtime.state.phase != TransferAttempt.Phase.ACCEPTED) return
                    if (!validSize(runtime, update.totalBytes) || update.bytesTransferred < 0 || update.bytesTransferred > update.totalBytes) {
                        fail(runtime, "The file size does not match its offer."); return
                    }
                    runtime.total = update.totalBytes
                    transfers.progress(runtime.sessionId, update.bytesTransferred, update.totalBytes)
                    armTimeout(runtime, 90_000, "File transmission stalled.")
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    if (!validSize(runtime, update.totalBytes) || update.bytesTransferred != update.totalBytes) {
                        fail(runtime, "The completed file size does not match its offer."); return
                    }
                    if (!runtime.state.delivery()) return
                    runtime.total = update.totalBytes
                    transfers.progress(runtime.sessionId, update.bytesTransferred, update.totalBytes)
                    transfers.saving(runtime.sessionId)
                    if (runtime.state.outgoing) {
                        armTimeout(runtime, 120_000, "The receiver did not confirm a successful save.")
                        maybeCompleteSender(runtime)
                    } else maybeSave(runtime)
                }
                PayloadTransferUpdate.Status.FAILURE -> fail(runtime, "Nearby file transmission failed.")
                PayloadTransferUpdate.Status.CANCELED -> settle(runtime, cancelled = true, notify = "cancel", message = "The file transmission was cancelled.")
            }
        }
    }

    private fun validSize(runtime: Runtime, total: Long): Boolean =
        total in 0..TransferProtocol.MAX_FILE_SIZE && (runtime.info.size == -1L || runtime.info.size == total)

    private fun receiveControl(frame: TransferProtocol.Frame) {
        if (frame.type == "offer") { receiveOffer(frame); return }
        val runtime = attempts[frame.id] ?: return
        if (!runtime.state.matches(frame.id, frame.payloadId, generation)) return
        when (frame.type) {
            "accept" -> {
                if (!runtime.state.outgoing || sendQueue.active != runtime.sessionId || !runtime.state.accept()) return
                val endpoint = connectedEndpoint ?: return
                val payload = runtime.outgoingPayload ?: return
                runtime.state.start()
                transfers.running(runtime.sessionId)
                armTimeout(runtime, 90_000, "The file transmission did not start.")
                try {
                    client.sendPayload(endpoint, payload).addOnFailureListener {
                        if (live(runtime)) fail(runtime, "Cannot send file: ${it.message}")
                    }
                } catch (e: Exception) { fail(runtime, "Cannot send file: ${e.message}") }
            }
            "reject" -> settle(runtime, cancelled = true, message = frame.reason.ifBlank { "Receiver rejected this file." })
            "cancel" -> settle(runtime, cancelled = true, message = frame.reason.ifBlank { "The peer cancelled this file." })
            "error" -> fail(runtime, frame.reason.ifBlank { "The peer could not complete this transfer." }, notify = false)
            "saved" -> {
                if (!runtime.state.outgoing || !validSize(runtime, frame.size)) {
                    fail(runtime, "Invalid receiver save confirmation."); return
                }
                runtime.savedSize = frame.size
                if (runtime.state.acknowledgeSave()) maybeCompleteSender(runtime)
            }
        }
    }

    private fun receiveOffer(frame: TransferProtocol.Frame) {
        val duplicate = attempts[frame.id]
        if (duplicate != null && !duplicate.state.outgoing && duplicate.state.payloadId == frame.payloadId) {
            fail(duplicate, "A duplicate file offer was received.")
            return
        }
        if (frame.id in seenIds || frame.payloadId in seenPayloadIds || attempts.containsPayload(frame.payloadId) ||
            textDeliveries.containsKey(frame.payloadId) || controls.containsKey(frame.payloadId) || receivedTexts.containsKey(frame.payloadId) ||
            transfers.queue.items.value.any { it.id == frame.id } || seenIds.size >= 256 ||
            attempts.values.count { !it.state.outgoing } >= 32) {
            sendControl(frame.copy(type = "reject", reason = "Duplicate offer or receiver queue is full."))
            report("Rejected a duplicate offer or a full receive queue.")
            return
        }
        seenIds += frame.id; seenPayloadIds += frame.payloadId
        val info = IncomingFile(frame.id, frame.name, frame.size, frame.mime, _peerName.value, frame.payloadId,
            frame.batchId, frame.batchIndex, frame.batchCount, frame.batchSize)
        val runtime = Runtime(TransferAttempt(frame.id, frame.payloadId, generation, outgoing = false), frame.id, info)
        if (!attempts.put(runtime)) return
        transfers.enqueue(TransferSession(id = frame.id, name = frame.name, size = frame.size,
            mime = frame.mime, direction = TransferDirection.RECEIVED, peer = info.sender,
            batchId = frame.batchId, batchIndex = frame.batchIndex, batchCount = frame.batchCount, batchSize = frame.batchSize))
        transfers.waiting(frame.id)
        receiveQueue.enqueue(frame.id)
        showNextOffer()
        armTimeout(runtime, 120_000, "The incoming offer expired without a decision.")
    }

    private fun showNextOffer() {
        val id = receiveQueue.next()
        _incomingFile.value = id?.let(attempts::get)?.info
    }

    fun acceptFile(info: IncomingFile) {
        val runtime = attempts[info.transferId] ?: return
        if (info != runtime.info || receiveQueue.active != info.transferId || !live(runtime) || !runtime.state.accept()) return
        transfers.running(runtime.sessionId)
        _incomingFile.value = null
        receiveQueue.finish(info.transferId)
        showNextOffer()
        armTimeout(runtime, 90_000, "The sender did not send the accepted file.")
        sendControl(TransferProtocol.Frame("accept", runtime.state.id, runtime.state.payloadId), runtime)
    }

    fun rejectFile(info: IncomingFile) {
        val runtime = attempts[info.transferId] ?: return
        if (info != runtime.info || runtime.state.outgoing || !live(runtime)) return
        settle(runtime, cancelled = true, notify = "reject", message = "Receiver rejected this file.")
    }

    /** Metadata is offered first. Creating a Payload does not send any bytes. */
    fun sendFile(transferId: String, meta: JSONObject, file: File) {
        if (connectedEndpoint == null) { transfers.fail(transferId, "Not connected"); report("Connect before sending files."); deleteOwned(file); return }
        val session = transfers.queue.items.value.firstOrNull { it.id == transferId }
        if (session == null || session.direction != TransferDirection.SENT || session.status != TransferStatus.QUEUED) {
            report("This transfer is not a fresh queued sender file.")
            if (sources[transferId]?.file != file) deleteOwned(file)
            return
        }
        val name = meta.optString("name")
        val mime = meta.optString("mime", "application/octet-stream")
        val size = meta.optLong("size", -1)
        if (!TransferProtocol.validMetadata(name, size, mime) || !file.isFile ||
            (size >= 0 && file.length() != size) || file.length() > TransferProtocol.MAX_FILE_SIZE) {
            transfers.fail(transferId, "Invalid file metadata or unreadable source.")
            report("Invalid file metadata or unreadable source."); deleteOwned(file); return
        }
        if (sources.containsKey(transferId) || sendQueue.snapshot().size >= 32) {
            if (!sources.containsKey(transferId)) { transfers.fail(transferId, "Send queue is full."); deleteOwned(file) }
            report("This file is already queued or the send queue is full."); return
        }
        sources[transferId] = Source(name, size, mime, file)
        sendQueue.enqueue(transferId)
        startNextFile()
    }

    private fun startNextFile() {
        if (settlingAll || connectedEndpoint == null) return
        val sessionId = sendQueue.next() ?: return
        if (attempts.values.any { it.sessionId == sessionId && it.state.outgoing }) return
        val source = sources[sessionId] ?: run { sendQueue.finish(sessionId); startNextFile(); return }
        try {
            val payload = Payload.fromFile(source.file)
            val wireId = UUID.randomUUID().toString() // Each retry has a fresh identity and Nearby payload ID.
            val session = transfers.queue.items.value.first { it.id == sessionId }
            val info = IncomingFile(wireId, source.name, source.size, source.mime, _peerName.value, payload.id)
            val runtime = Runtime(TransferAttempt(wireId, payload.id, generation, outgoing = true), sessionId, info, payload)
            check(attempts.put(runtime)) { "Duplicate Nearby file payload identity" }
            transfers.waiting(sessionId)
            armTimeout(runtime, 120_000, "The receiver did not accept the file offer.")
            sendControl(TransferProtocol.Frame("offer", wireId, payload.id, source.name, source.size, source.mime,
                batchId = session.batchId, batchIndex = session.batchIndex, batchCount = session.batchCount,
                batchSize = session.batchSize.takeIf { it >= source.size && it <= TransferProtocol.MAX_FILE_SIZE * session.batchCount } ?: -1), runtime)
        } catch (e: Exception) {
            transfers.fail(sessionId, "Cannot prepare file payload: ${e.message}")
            report("Cannot prepare file payload: ${e.message}")
            retainFailure(sessionId)
            sendQueue.finish(sessionId); startNextFile()
        }
    }

    fun sendText(text: String) {
        val endpoint = connectedEndpoint ?: run { report("Connect before sending text."); return }
        if (text.isBlank()) { report("Enter some text to send."); return }
        if (textDeliveries.size >= 16) { report("Too many text transfers are pending."); return }
        val id = UUID.randomUUID().toString()
        // JSON escaping and multibyte characters count towards the actual SDK bytes limit.
        val bytes = TransferProtocol.encode(TransferProtocol.Frame("text", id, text = text))
        if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) { report("Text is too large for a Nearby message."); return }
        val payload = Payload.fromBytes(bytes)
        val delivery = TextDelivery(id, generation)
        textDeliveries[payload.id] = delivery
        transfers.enqueue(TransferSession(id = id, name = "Text (${text.length} chars)",
            size = bytes.size.toLong(), mime = "text/plain", peer = _peerName.value, isText = true))
        transfers.running(id)
        delivery.timeout = scope.launch {
            delay(60_000)
            if (textDeliveries.remove(payload.id) === delivery) {
                cancelPayload(payload.id); transfers.fail(id, "Text delivery timed out."); report("Text delivery timed out.")
            }
        }
        fun failed(message: String) {
            if (textDeliveries.remove(payload.id) !== delivery) return
            delivery.timeout?.cancel(); transfers.fail(id, message); report(message)
        }
        try {
            client.sendPayload(endpoint, payload).addOnFailureListener { failed("Cannot send text: ${it.message}") }
        } catch (e: Exception) { failed("Cannot send text: ${e.message}") }
        // Task success only means dispatch accepted. Completion belongs to the payload SUCCESS callback.
    }

    private fun receiveText(payloadId: Long, frame: TransferProtocol.Frame) {
        if (frame.id in seenIds || seenIds.size >= 256 || receivedTexts.size + pendingTextDialogs.size + (if (_incomingText.value == null) 0 else 1) >= 16 ||
            transfers.queue.items.value.any { it.id == frame.id }) {
            report("Rejected duplicate text or a full receive queue."); return
        }
        seenIds += frame.id
        val sender = _peerName.value
        receivedTexts[payloadId] = ReceivedText(frame.id, frame.text, sender)
        transfers.enqueue(TransferSession(id = frame.id, name = "Text (${frame.text.length} chars)",
            size = frame.text.toByteArray(Charsets.UTF_8).size.toLong(), mime = "text/plain",
            direction = TransferDirection.RECEIVED, peer = sender, isText = true))
        transfers.running(frame.id)
        val token = generation
        scope.launch {
            delay(60_000)
            if (token == generation && receivedTexts.remove(payloadId)?.id == frame.id) {
                transfers.fail(frame.id, "Text receive timed out.")
            }
        }
    }

    private fun sendControl(frame: TransferProtocol.Frame, runtime: Runtime? = null) {
        val endpoint = connectedEndpoint ?: return
        val token = generation
        val bytes = TransferProtocol.encode(frame)
        if (bytes.size > TransferProtocol.MAX_CONTROL_BYTES) {
            if (runtime != null) fail(runtime, "Transfer metadata is too large.", notify = false)
            return
        }
        val payload = Payload.fromBytes(bytes)
        if (runtime != null) controls[payload.id] = ControlDelivery(runtime.state.id, token)
        try {
            client.sendPayload(endpoint, payload).addOnFailureListener {
                controls.remove(payload.id)
                if (token != generation) return@addOnFailureListener
                if (runtime != null && live(runtime)) fail(runtime, "Cannot send transfer message: ${it.message}", notify = false)
                else report("Could not notify the other device: ${it.message}")
            }
        } catch (e: Exception) {
            controls.remove(payload.id)
            if (runtime != null && live(runtime)) fail(runtime, "Cannot send transfer message: ${e.message}", notify = false)
            else report("Could not notify the other device: ${e.message}")
        }
    }

    private fun live(runtime: Runtime): Boolean = runtime.state.generation == generation &&
        attempts[runtime.state.id] === runtime && runtime.state.phase != TransferAttempt.Phase.TERMINAL

    private fun armTimeout(runtime: Runtime, millis: Long, message: String) {
        runtime.timeout?.cancel()
        runtime.timeout = scope.launch {
            delay(millis)
            if (live(runtime)) fail(runtime, message)
        }
    }

    private fun maybeSave(runtime: Runtime) {
        if (!live(runtime) || runtime.state.outgoing || !runtime.state.delivered || runtime.saveJob != null) return
        val payload = runtime.receivedPayload ?: run {
            armTimeout(runtime, 30_000, "Nearby completed a file without a readable file payload."); return
        }
        val sourceUri = receivedUri(payload) ?: run { fail(runtime, "The received file is unreadable."); return }
        transfers.saving(runtime.sessionId)
        armTimeout(runtime, 120_000, "Saving the received file timed out.")
        runtime.saveJob = scope.launch {
            try {
                val saved = withTimeout(120_000) { files.saveReceived(sourceUri, runtime.info.name, runtime.info.mime) }
                if (!live(runtime)) { deleteReceivedUri(saved.uri); return@launch }
                if (!validSize(runtime, saved.size) || runtime.total != saved.size) {
                    deleteReceivedUri(saved.uri)
                    runtime.saveJob = null
                    fail(runtime, "The saved file size does not match the transmitted file.")
                    return@launch
                }
                runtime.saveJob = null
                // Save acknowledgement is sent only after durable persistence, never on transport SUCCESS.
                sendControl(TransferProtocol.Frame("saved", runtime.state.id, runtime.state.payloadId, size = saved.size))
                settle(runtime, outputUri = saved.uri.toString())
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException && live(runtime)) {
                    runtime.saveJob = null; fail(runtime, "Saving the received file timed out.")
                }
                throw e
            } catch (e: Exception) {
                runtime.saveJob = null
                if (live(runtime)) fail(runtime, "Could not save the received file: ${e.message ?: "storage is unavailable"}")
            }
        }
    }

    private fun maybeCompleteSender(runtime: Runtime) {
        if (runtime.state.outgoing && runtime.state.delivered && runtime.state.saved && live(runtime)) {
            if (runtime.savedSize != runtime.total) fail(runtime, "The receiver's saved bytes do not match the delivered file.")
            else settle(runtime)
        }
    }

    private fun fail(runtime: Runtime, message: String, notify: Boolean = true) {
        settle(runtime, failure = message, notify = if (notify) "error" else null, message = message)
    }

    /** The single terminal gate removes all old payload mappings before the next queue item starts. */
    private fun settle(
        runtime: Runtime,
        failure: String? = null,
        cancelled: Boolean = false,
        notify: String? = null,
        message: String = "",
        outputUri: String? = null,
    ) {
        val wasOffered = runtime.state.phase == TransferAttempt.Phase.OFFERED
        if (!runtime.state.finish()) return
        runtime.timeout?.cancel(); runtime.saveJob?.cancel()
        attempts.remove(runtime.state.id)
        controls.entries.removeAll { it.value.wireId == runtime.state.id }
        if (notify != null) sendControl(TransferProtocol.Frame(notify, runtime.state.id, runtime.state.payloadId, reason = message.take(300)))
        if (!wasOffered && (failure != null || cancelled)) cancelPayload(runtime.state.payloadId)
        when {
            failure != null -> { transfers.fail(runtime.sessionId, failure); report(failure) }
            cancelled -> transfers.cancel(runtime.sessionId)
            else -> transfers.complete(runtime.sessionId, outputUri)
        }
        runtime.outgoingPayload?.close()
        runtime.receivedPayload?.let(::cleanReceived)
        if (runtime.state.outgoing) {
            if (failure != null) retainFailure(runtime.sessionId)
            else releaseSource(runtime.sessionId)
            sendQueue.finish(runtime.sessionId)
        } else {
            receiveQueue.finish(runtime.state.id)
            if (_incomingFile.value?.transferId == runtime.state.id) _incomingFile.value = null
        }
        if (!settlingAll) { showNextOffer(); startNextFile() }
    }

    fun cancelTransfer(transferId: String) {
        val runtime = attempts.values.firstOrNull { it.sessionId == transferId }
        if (runtime != null) {
            settle(runtime, cancelled = true, notify = "cancel", message = "Cancelled by the user.")
            return
        }
        val text = textDeliveries.entries.firstOrNull { it.value.sessionId == transferId }
        if (text != null) {
            textDeliveries.remove(text.key); text.value.timeout?.cancel()
            cancelPayload(text.key); transfers.cancel(transferId); return
        }
        if (transferId in sendQueue.snapshot()) {
            sendQueue.finish(transferId); releaseSource(transferId); transfers.cancel(transferId); startNextFile()
        } else if (transferId in failedSources) releaseSource(transferId)
        else if (transfers.queue.items.value.any { it.id == transferId && it.status == TransferStatus.QUEUED }) {
            // The ViewModel may still be preparing this staged source asynchronously.
            transfers.cancel(transferId)
        }
    }

    fun cancelAllTransfers() { settleAll("Cancelled by the user.", cancelled = true) }

    private fun settleAll(message: String, cancelled: Boolean) {
        settlingAll = true
        attempts.values.toList().forEach {
            settle(it, failure = if (cancelled) null else message, cancelled = cancelled,
                notify = if (connectedEndpoint != null) "cancel" else null, message = message)
        }
        sendQueue.snapshot().forEach {
            if (cancelled) transfers.cancel(it) else transfers.fail(it, message)
            releaseSource(it)
        }
        textDeliveries.forEach { (payloadId, delivery) ->
            delivery.timeout?.cancel(); cancelPayload(payloadId)
            if (cancelled) transfers.cancel(delivery.sessionId) else transfers.fail(delivery.sessionId, message)
        }
        receivedTexts.values.forEach {
            if (cancelled) transfers.cancel(it.id) else transfers.fail(it.id, message)
        }
        transfers.queue.items.value.filter {
            it.status in setOf(TransferStatus.QUEUED, TransferStatus.WAITING, TransferStatus.RUNNING, TransferStatus.SAVING)
        }.forEach {
            if (cancelled) transfers.cancel(it.id) else transfers.fail(it.id, message)
        }
        textDeliveries.clear(); receivedTexts.clear(); sendQueue.clear(); receiveQueue.clear()
        attempts.clear(); controls.clear()
        // Explicit cancellation/disconnection ends retry ownership as well.
        sources.keys.toList().forEach(::releaseSource)
        _incomingFile.value = null
        settlingAll = false
    }

    /** Receiver/text retries are intentionally unsupported; only bounded retained sender files retry. */
    fun retryTransfer(transferId: String): Boolean {
        val source = sources[transferId] ?: return false
        val session = transfers.queue.items.value.firstOrNull { it.id == transferId } ?: return false
        if (connectedEndpoint == null || transferId !in failedSources || !source.file.isFile ||
            session.status != TransferStatus.FAILED || sendQueue.snapshot().size >= 32) return false
        failedSources.remove(transferId)?.cancel()
        transfers.retry(transferId)
        sendQueue.enqueue(transferId)
        startNextFile()
        return true
    }

    fun canRetryTransfer(transferId: String): Boolean = connectedEndpoint != null &&
        transferId in failedSources && sources[transferId]?.file?.isFile == true &&
        transfers.queue.items.value.any { it.id == transferId && it.status == TransferStatus.FAILED }

    private fun retainFailure(id: String) {
        failedSources.remove(id)?.cancel()
        val source = sources[id] ?: return
        if (source.file.length() > 512L * 1024 * 1024) { releaseSource(id); return }
        failedSources[id] = scope.launch {
            delay(5 * 60_000L)
            releaseSource(id)
        }
        while (failedSources.size > 3 || failedSources.keys.sumOf { sources[it]?.file?.length() ?: 0L } > 512L * 1024 * 1024) {
            releaseSource(failedSources.keys.first())
        }
    }

    private fun releaseSource(id: String) {
        failedSources.remove(id)?.cancel()
        sources.remove(id)?.file?.let(::deleteOwned)
    }

    private fun deleteOwned(file: File) {
        scope.launch(Dispatchers.IO) {
            try {
                val cache = context.cacheDir.canonicalFile
                if (file.canonicalFile.path.startsWith(cache.path + File.separator)) file.delete()
            } catch (_: Exception) { }
        }
    }

    private fun rejectPayload(payload: Payload) {
        cancelPayload(payload.id)
        if (payload.type == Payload.Type.FILE) cleanReceived(payload) else payload.close()
    }

    private fun cancelPayload(id: Long) {
        val token = generation
        try {
            client.cancelPayload(id).addOnFailureListener {
                if (token == generation && connectedEndpoint != null) report("Nearby could not abort a payload: ${it.message}")
            }
        } catch (e: Exception) {
            if (token == generation && connectedEndpoint != null) report("Nearby could not abort a payload: ${e.message}")
        }
    }

    private fun cleanReceived(payload: Payload) {
        val uri = receivedUri(payload)
        scope.launch(Dispatchers.IO) {
            try {
                if (uri != null) {
                    if (uri.scheme == "file") uri.path?.let { File(it).delete() }
                    else context.contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) { /* Some Nearby providers only support close(). */ }
            finally { payload.close() }
        }
    }

    private suspend fun deleteReceivedUri(uri: Uri) = withContext(Dispatchers.IO) {
        try {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) { }
    }

    /** Dismissing a file consent dialog is rejection, not an invisible stranded offer. */
    fun dismissIncoming() {
        _incomingFile.value?.let(::rejectFile)
        dismissText()
    }

    fun dismissText() { _incomingText.value = pendingTextDialogs.removeFirstOrNull() }

    @Suppress("DEPRECATION")
    private fun receivedUri(payload: Payload): Uri? = payload.asFile()?.let { file ->
        file.asUri() ?: if (android.os.Build.VERSION.SDK_INT < 29) file.asJavaFile()?.let(Uri::fromFile) else null
    }

    fun endpointName(id: String): String = endpointNames[id] ?: id
}
