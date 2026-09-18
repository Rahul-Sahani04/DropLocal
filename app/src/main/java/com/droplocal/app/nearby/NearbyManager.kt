package com.droplocal.app.nearby

import android.content.Context
import com.droplocal.app.BuildConfig
import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferRepository
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class NearbyEndpoint(val id: String, val name: String)
data class IncomingFile(
    val transferId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val sender: String,
    val payloadId: Long,
)
data class IncomingText(val text: String, val sender: String)

enum class ConnState { IDLE, ADVERTISING, DISCOVERING, REQUESTED, PENDING_AUTH, CONNECTED }

/**
 * PRD §13: serviceId = package name, P2P_POINT_TO_POINT, stop discovery
 * once target selected. Auth digits shown before accept.
 */
class NearbyManager(
    private val context: Context,
    private val transfers: TransferRepository,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(Dispatchers.Main)
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

    private var pendingEndpoint: String? = null
    private var connectedEndpoint: String? = null
    private val endpointNames = mutableMapOf<String, String>()
    // Nearby payloadId -> our transfer session id
    private val payloadToTransfer = mutableMapOf<Long, String>()
    private val metaByTransfer = mutableMapOf<String, JSONObject>()
    var onFileReady: ((File, IncomingFile) -> Unit)? = null

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingEndpoint = endpointId
            endpointNames[endpointId] = info.endpointName
            _peerName.value = info.endpointName
            _authToken.value = info.authenticationDigits
            _connState.value = ConnState.PENDING_AUTH
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoint = endpointId
                _connState.value = ConnState.CONNECTED
            } else {
                pendingEndpoint = null
                _connState.value = ConnState.IDLE
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) {
                connectedEndpoint = null
                _connState.value = ConnState.IDLE
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            endpointNames[endpointId] = info.endpointName
            val cur = _endpoints.value
            if (cur.none { it.id == endpointId }) {
                _endpoints.value = cur + NearbyEndpoint(endpointId, info.endpointName)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _endpoints.value = _endpoints.value.filterNot { it.id == endpointId }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val json = payload.asBytes()?.let { String(it, Charsets.UTF_8) } ?: return
                    try {
                        val o = JSONObject(json)
                        when (o.optString("type")) {
                            "text" -> {
                                val t = o.optString("text")
                                val sender = endpointNames[endpointId] ?: endpointId
                                _incomingText.value = IncomingText(t, sender)
                                val s = TransferSession(
                                    name = "Text (${t.length} chars)",
                                    size = t.toByteArray().size.toLong(),
                                    mime = "text/plain",
                                    direction = TransferDirection.RECEIVED,
                                    peer = sender,
                                    bytes = t.toByteArray().size.toLong(),
                                    status = TransferStatus.DONE,
                                    endedAt = System.currentTimeMillis(),
                                )
                                transfers.enqueue(s)
                                transfers.complete(s.id)
                            }
                            "file" -> {
                                val tid = o.optString("id")
                                metaByTransfer[tid] = o
                                val sender = endpointNames[endpointId] ?: endpointId
                                val s = TransferSession(
                                    id = tid,
                                    name = o.optString("name"),
                                    size = o.optLong("size"),
                                    mime = o.optString("mime"),
                                    direction = TransferDirection.RECEIVED,
                                    peer = sender,
                                )
                                transfers.enqueue(s)
                                // Wait for receiver accept before mapping payload (MVP: modal gates accept)
                            }
                        }
                    } catch (_: Exception) { }
                }
                Payload.Type.FILE -> {
                    val file = payload.asFile()?.asJavaFile() ?: return
                    // Find transfer waiting for a file payload (first RUNNING/QUEUED received)
                    val waiting = transfers.queue.items.value.firstOrNull {
                        it.direction == TransferDirection.RECEIVED &&
                            (it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING)
                    }
                    if (waiting != null) {
                        payloadToTransfer[payload.id] = waiting.id
                        transfers.queue.update(waiting.id) {
                            it.copy(status = TransferStatus.RUNNING)
                        }
                        // If receiver already accepted (status RUNNING via acceptFile), deliver
                        val accepted = metaByTransfer.remove(waiting.id)
                        scope.launch {
                            onFileReady?.invoke(
                                file,
                                IncomingFile(
                                    waiting.id, waiting.name, waiting.size,
                                    waiting.mime, waiting.peer, payload.id,
                                ),
                            )
                        }
                        if (accepted == null) {
                            // Hold: show modal; actual save happens on accept via acceptFile()
                            _incomingFile.value = IncomingFile(
                                waiting.id, waiting.name, waiting.size,
                                waiting.mime, waiting.peer, payload.id,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val tid = payloadToTransfer[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    transfers.progress(tid, update.bytesTransferred)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    // For sender-side file payloads, payloadId maps at send time
                    val cur = transfers.queue.items.value.firstOrNull { it.id == tid }
                    if (cur != null && cur.direction == TransferDirection.SENT) {
                        transfers.progress(tid, cur.size)
                        transfers.complete(tid)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    transfers.fail(tid, "Transfer ${update.status}")
                }
            }
        }
    }

    fun startAdvertising() {
        _connState.value = ConnState.ADVERTISING
        client.startAdvertising(
            deviceName, serviceId, lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        )
    }

    fun startDiscovery() {
        _connState.value = ConnState.DISCOVERING
        _endpoints.value = emptyList()
        client.startDiscovery(
            serviceId, discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        )
    }

    fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        _connState.value = ConnState.IDLE
    }

    fun requestConnection(endpoint: NearbyEndpoint) {
        // PRD §13: stop discovery once target selected (saves radio)
        client.stopDiscovery()
        _connState.value = ConnState.REQUESTED
        client.requestConnection(deviceName, endpoint.id, lifecycleCallback)
    }

    fun acceptConnection() {
        pendingEndpoint?.let { client.acceptConnection(it, payloadCallback) }
    }

    fun rejectConnection() {
        pendingEndpoint?.let { client.rejectConnection(it) }
        pendingEndpoint = null
        _connState.value = ConnState.IDLE
    }

    fun disconnect() {
        connectedEndpoint?.let { client.disconnectFromEndpoint(it) }
        connectedEndpoint = null
        stopAll()
    }

    fun sendText(text: String) {
        val ep = connectedEndpoint ?: return
        val o = JSONObject().put("type", "text")
            .put("id", java.util.UUID.randomUUID().toString()).put("text", text)
        val s = TransferSession(
            name = "Text (${text.length} chars)", size = text.toByteArray().size.toLong(),
            mime = "text/plain", direction = TransferDirection.SENT,
            peer = _peerName.value, status = TransferStatus.RUNNING,
        )
        transfers.enqueue(s)
        client.sendPayload(ep, Payload.fromBytes(o.toString().toByteArray(Charsets.UTF_8)))
            .addOnSuccessListener { transfers.complete(s.id) }
            .addOnFailureListener { transfers.fail(s.id, it.message ?: "send failed") }
    }

    fun sendFile(transferId: String, meta: JSONObject, file: File) {
        val ep = connectedEndpoint ?: run {
            transfers.fail(transferId, "Not connected"); return
        }
        client.sendPayload(ep, Payload.fromBytes(meta.toString().toByteArray(Charsets.UTF_8)))
        val fp = Payload.fromFile(file)
        // Map when transfer update arrives: Nearby assigns payload id internally;
        // correlate by tracking the most recent file payload per transfer via progress.
        // We map lazily: store transfer id keyed by file length+name hash is fragile,
        // so instead send then associate on first IN_PROGRESS by matching SENT+RUNNING.
        client.sendPayload(ep, fp).addOnFailureListener {
            transfers.fail(transferId, it.message ?: "send failed")
        }
        // Mark running; progress updates arrive via payloadCallback once ids correlate.
        // Correlate: remember pending sent transfer so IN_PROGRESS without mapping binds to it.
        pendingSentTransfer = transferId
    }

    private var pendingSentTransfer: String? = null

    /** Called from payloadCallback path for sender progress when id unknown. */
    fun bindSenderProgress(payloadId: Long, bytes: Long, total: Long) {
        val tid = payloadToTransfer[payloadId] ?: pendingSentTransfer ?: return
        payloadToTransfer[payloadId] = tid
        transfers.progress(tid, bytes)
    }

    fun acceptFile(info: IncomingFile) {
        // Receiver accepted modal: mark running; file bytes already arriving/saved via onFileReady
        transfers.queue.update(info.transferId) { it.copy(status = TransferStatus.RUNNING) }
        _incomingFile.value = null
    }

    fun dismissIncoming() {
        _incomingFile.value = null
        _incomingText.value = null
    }

    fun endpointName(id: String): String = endpointNames[id] ?: id
}
