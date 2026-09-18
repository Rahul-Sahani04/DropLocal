package com.droplocal.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.droplocal.app.DropLocalApp
import com.droplocal.app.nearby.IncomingFile
import com.droplocal.app.nearby.NearbyManager
import com.droplocal.app.pairing.QrPayload
import com.droplocal.app.storage.FileRepository
import com.droplocal.app.storage.PickedFile
import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferRepository
import com.droplocal.app.transfer.TransferSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

class MainViewModel : ViewModel() {
    lateinit var nearby: NearbyManager
    lateinit var transfers: TransferRepository
    lateinit var files: FileRepository
    lateinit var app: DropLocalApp

    private val _picked = MutableStateFlow<List<PickedFile>>(emptyList())
    val picked: StateFlow<List<PickedFile>> = _picked

    private val _qr = MutableStateFlow(QrPayload())
    val qr: StateFlow<QrPayload> = _qr

    private val _savedFile = MutableStateFlow<File?>(null)
    val savedFile: StateFlow<File?> = _savedFile

    fun attach(app: DropLocalApp) {
        if (::nearby.isInitialized) return
        this.app = app
        nearby = app.nearby
        transfers = app.transfers
        files = FileRepository(app)
        nearby.onFileReady = { src, info ->
            val dest = try {
                files.saveToDownloads(src, info.name.ifEmpty { "droplocal_${info.transferId.take(6)}" })
            } catch (_: Exception) { src }
            _savedFile.value = dest
            transfers.progress(info.transferId, info.size)
            transfers.complete(info.transferId)
        }
        renewQr()
    }

    fun renewQr() { _qr.value = QrPayload(device = nearby.deviceName) }

    fun addPicked(uris: List<Uri>) {
        _picked.value = _picked.value + uris.map { files.inspect(it) }
    }

    fun clearPicked() { _picked.value = emptyList() }

    fun sendPickedAsQueue() {
        for (p in _picked.value) {
            val tid = java.util.UUID.randomUUID().toString()
            val meta = JSONObject()
                .put("type", "file").put("id", tid)
                .put("name", p.name).put("size", p.size).put("mime", p.mime)
            val session = TransferSession(
                id = tid, name = p.name, size = p.size, mime = p.mime,
                direction = TransferDirection.SENT, peer = nearby.peerName.value,
                status = com.droplocal.app.transfer.TransferStatus.RUNNING,
            )
            transfers.enqueue(session)
            try {
                val cached = files.copyToCache(p.uri, p.name)
                nearby.sendFile(tid, meta, cached)
            } catch (e: Exception) {
                transfers.fail(tid, e.message ?: "read failed")
            }
        }
    }

    fun acceptIncoming(info: IncomingFile) = nearby.acceptFile(info)
    fun sendText(t: String) = nearby.sendText(t)
}
