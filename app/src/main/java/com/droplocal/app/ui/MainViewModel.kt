package com.droplocal.app.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droplocal.app.DropLocalApp
import com.droplocal.app.nearby.ConnState
import com.droplocal.app.storage.FileRepository
import com.droplocal.app.storage.PickedFile
import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Content selection survives rotation; transport/persistence are application-owned. */
class MainViewModel(private val state: SavedStateHandle) : ViewModel() {
    sealed interface Event {
        data object ChooseDevice : Event
        data object ShowTransfers : Event
    }
    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    val draft = state.getStateFlow("draft", "")
    val displayBatchId = state.getStateFlow<String?>("displayBatch", null)
    private val _picked = MutableStateFlow<List<PickedFile>>(emptyList())
    val picked: StateFlow<List<PickedFile>> = _picked
    private val _inspecting = MutableStateFlow(false)
    val inspecting: StateFlow<Boolean> = _inspecting
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private var app: DropLocalApp? = null
    private var files: FileRepository? = null
    private var preparation: Job? = null
    private var selectionGeneration = 0
    private var sentDraft: Pair<String, String>? = null

    fun updateDraft(value: String) { state["draft"] = value; clearError() }
    fun clearError() { _error.value = null }
    fun report(message: String) { _error.value = message }
    fun attach(application: DropLocalApp) {
        if (app != null) return
        app = application
        files = FileRepository(application)
        // File URI selections are intentionally not restored across process death without re-selection.
        if (state.get<String>("pending") == "files") {
            state["pending"] = null
            report("The previous file selection could not be restored. Choose the files again.")
        }
        viewModelScope.launch {
            application.nearby.connState.collect { if (it == ConnState.CONNECTED) dispatchPending() }
        }
        viewModelScope.launch {
            application.transfers.queue.items.collect { sessions ->
                sentDraft?.let { (id, text) ->
                    if (sessions.any { it.id == id && it.status == TransferStatus.DONE }) {
                        if (draft.value == text) state["draft"] = ""
                        sentDraft = null
                    }
                }
            }
        }
    }

    fun selectFiles(uris: List<Uri>) {
        val application = app ?: return
        if (hasActiveTransfers()) { report("Finish or cancel the current transfers first."); return }
        if (uris.size > 32) { report("Choose up to 32 files per batch."); return }
        cancelPending()
        clearError()
        val generation = selectionGeneration
        _inspecting.value = true
        preparation = viewModelScope.launch {
            try {
                val selected = uris.map { files!!.inspect(it) }
                if (generation != selectionGeneration) return@launch
                _picked.value = selected
                state["pending"] = "files"
                if (application.nearby.connState.value == ConnState.CONNECTED) dispatchPending()
                else eventChannel.send(Event.ChooseDevice)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report("Could not read the selected files: ${e.message ?: "choose them again"}") }
            finally { if (generation == selectionGeneration) _inspecting.value = false }
        }
    }

    fun chooseDeviceForText(text: String) {
        val application = app ?: return
        if (hasActiveTransfers()) { report("Finish or cancel the current transfers first."); return }
        if (text.isBlank() || application.nearby.encodedTextSize(text) > application.nearby.maxTextBytes) {
            report("Enter text within the displayed byte limit."); return
        }
        clearError()
        state["pending"] = "text"
        state["pendingText"] = text
        if (application.nearby.connState.value == ConnState.CONNECTED) dispatchPending()
        else viewModelScope.launch { eventChannel.send(Event.ChooseDevice) }
    }

    fun dispatchPending() {
        val application = app ?: return
        if (application.nearby.connState.value != ConnState.CONNECTED) return
        when (state.get<String>("pending")) {
            "files" -> {
                val selected = _picked.value
                if (selected.isEmpty()) return
                state["pending"] = null
                sendFiles(selected)
            }
            "text" -> {
                val text = state.get<String>("pendingText") ?: return
                state["pending"] = null
                state["pendingText"] = null
                val previousId = application.transfers.queue.items.value.lastOrNull()?.id
                application.nearby.sendText(text)
                val session = application.transfers.queue.items.value.lastOrNull()
                if (session?.isText == true && session.id != previousId && session.direction == TransferDirection.SENT) {
                    sentDraft = session.id to text
                    showTransfer(session.id)
                    viewModelScope.launch { eventChannel.send(Event.ShowTransfers) }
                }
            }
        }
    }

    private fun sendFiles(selected: List<PickedFile>) {
        val application = app ?: return
        val batchId = UUID.randomUUID().toString()
        val total = if (selected.any { it.size < 0 }) -1L else selected.fold(0L) { sum, file ->
            if (sum < 0 || file.size > Long.MAX_VALUE - sum) -1L else sum + file.size
        }
        val staged = selected.mapIndexed { index, picked ->
            TransferSession(name = picked.name, size = picked.size, mime = picked.mime,
                peer = application.nearby.peerName.value, batchId = batchId,
                batchIndex = index + 1, batchCount = selected.size, batchSize = total) to picked
        }
        staged.forEach { application.transfers.enqueue(it.first) }
        showTransfer(batchId)
        val generation = selectionGeneration
        preparation = viewModelScope.launch {
            eventChannel.send(Event.ShowTransfers)
            for ((session, picked) in staged) {
                var cached: File? = null
                var handedOff = false
                try {
                    ensureActive()
                    if (generation != selectionGeneration || isTerminal(session.id)) continue
                    cached = files!!.copyToCache(picked.uri, picked.name)
                    ensureActive()
                    if (generation != selectionGeneration || isTerminal(session.id)) continue
                    val actualSize = cached.length()
                    application.transfers.queue.update(session.id) { it.copy(size = actualSize) }
                    val meta = JSONObject().put("name", picked.name).put("size", actualSize).put("mime", picked.mime)
                    application.nearby.sendFile(session.id, meta, cached)
                    handedOff = true
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    application.transfers.fail(session.id, "Could not prepare file: ${e.message ?: "choose the file again"}")
                } finally {
                    if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { cached?.let { files?.deleteCached(it) } }
                }
            }
            _picked.value = emptyList()
        }
    }

    private fun isTerminal(id: String): Boolean = app?.transfers?.queue?.items?.value
        ?.firstOrNull { it.id == id }?.status?.isTerminal != false

    fun hasActiveTransfers(): Boolean = app?.transfers?.queue?.items?.value?.any { !it.status.isTerminal } == true
    fun pendingTitle(): String = if (state.get<String>("pending") == "text") "Send text" else "Send files"
    fun selectedSummary(): String = if (state.get<String>("pending") == "text") "Text ready to send" else
        _picked.value.takeIf { it.isNotEmpty() }?.let { "${it.size} file${if (it.size == 1) "" else "s"} selected" } ?: ""
    fun showTransfer(id: String) { state["displayBatch"] = id }
    fun cancelPending() {
        ++selectionGeneration
        preparation?.cancel()
        preparation = null
        _inspecting.value = false
        _picked.value = emptyList()
        state["pending"] = null
        state["pendingText"] = null
    }
    fun cancelAll() { cancelPending(); app?.nearby?.cancelAllTransfers(); app?.nearby?.disconnect() }
    fun clearHistory() { viewModelScope.launch { try { app?.history?.clear() } catch (_: Exception) { report("Could not clear history. Try again.") } } }
}
