package com.droplocal.app.nearby

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.droplocal.app.transfer.*
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

/** Exercises real manager callbacks/payloads with a fake radio client, not physical transport. */
@RunWith(AndroidJUnit4::class)
class NearbyManagerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var repository: TransferRepository
    private lateinit var manager: NearbyManager
    private lateinit var fake: RadioFixture
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fixtureFiles = mutableListOf<File>()

    private class RadioFixture {
        var failStarts = false
        val sent = mutableListOf<Payload>()
        lateinit var lifecycle: ConnectionLifecycleCallback
        lateinit var callback: PayloadCallback
        val client: ConnectionsClient = Proxy.newProxyInstance(ConnectionsClient::class.java.classLoader,
            arrayOf(ConnectionsClient::class.java)) { _, method, args ->
            when (method.name) {
                "startAdvertising", "requestConnection" -> {
                    lifecycle = args!![2] as ConnectionLifecycleCallback
                    if (failStarts) Tasks.forException<Void>(SecurityException("Synthetic missing permission")) else Tasks.forResult<Void>(null)
                }
                "acceptConnection" -> { callback = args!![1] as PayloadCallback; Tasks.forResult<Void>(null) }
                "sendPayload" -> { sent += args!![1] as Payload; Tasks.forResult<Void>(null) }
                "startDiscovery" -> if (failStarts) Tasks.forException<Void>(SecurityException("Synthetic missing permission")) else Tasks.forResult<Void>(null)
                "rejectConnection", "cancelPayload" -> Tasks.forResult<Void>(null)
                "toString" -> "SyntheticRadioClient"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        } as ConnectionsClient
    }

    private fun onMain(block: () -> Unit) { instrumentation.runOnMainSync(block); instrumentation.waitForIdleSync() }

    @Before fun connectAuthenticatedFixture() {
        fake = RadioFixture()
        repository = TransferRepository({}, writerScope)
        manager = NearbyManager(context, repository, client = fake.client)
        onMain { manager.startAdvertising() }
        onMain {
            fake.lifecycle.onConnectionInitiated("peer", ConnectionInfo(
                EndpointAdvertisement.encode(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000 + 120, "Fixture peer"), "1234", true))
            manager.acceptConnection()
            fake.lifecycle.onConnectionResult("peer", ConnectionResolution(Status.RESULT_SUCCESS))
        }
        assertEquals(ConnState.CONNECTED, manager.connState.value)
    }

    @After fun cleanup() {
        onMain { manager.disconnect() }
        writerScope.cancel()
        fixtureFiles.forEach { it.delete() }
    }

    private fun offerFile(): Pair<String, TransferProtocol.Frame> {
        val file = File.createTempFile("protocol_fixture_", ".bin", context.cacheDir).also { it.writeBytes(ByteArray(128) { 7 }); fixtureFiles += it }
        val session = TransferSession(name = "fixture.bin", size = file.length())
        onMain {
            repository.enqueue(session)
            manager.sendFile(session.id, JSONObject().put("name", session.name).put("size", session.size).put("mime", session.mime), file)
        }
        val offered = TransferProtocol.decode(fake.sent.single().asBytes()!!, manager.maxTextBytes)!!
        return session.id to offered
    }

    private fun accept(frame: TransferProtocol.Frame) = onMain {
        fake.callback.onPayloadReceived("peer", Payload.fromBytes(TransferProtocol.encode(
            TransferProtocol.Frame("accept", frame.id, frame.payloadId))))
    }
    private fun success(frame: TransferProtocol.Frame) = onMain {
        fake.callback.onPayloadTransferUpdate("peer", PayloadTransferUpdate.Builder().setPayloadId(frame.payloadId)
            .setTotalBytes(frame.size).setBytesTransferred(frame.size).setStatus(PayloadTransferUpdate.Status.SUCCESS).build())
    }

    @Test fun dispatchingOfferDoesNotSendFileUntilPeerAccepts() {
        val (id, offer) = offerFile()
        assertEquals(TransferStatus.WAITING, repository.queue.items.value.single().status)
        assertTrue(fake.sent.none { it.type == Payload.Type.FILE })
        accept(offer)
        assertEquals(offer.payloadId, fake.sent.last().id)
        assertEquals(Payload.Type.FILE, fake.sent.last().type)
        assertEquals(TransferStatus.RUNNING, repository.queue.items.value.first { it.id == id }.status)
    }

    @Test fun fileDoneRequiresBothDeliveryAndCorrectSavedAcknowledgement() {
        val (id, offer) = offerFile()
        accept(offer); success(offer)
        assertEquals(TransferStatus.SAVING, repository.queue.items.value.single().status)
        onMain { fake.callback.onPayloadReceived("peer", Payload.fromBytes(TransferProtocol.encode(
            TransferProtocol.Frame("saved", offer.id, offer.payloadId, size = offer.size)))) }
        assertEquals(TransferStatus.DONE, repository.queue.items.value.first { it.id == id }.status)
    }

    @Test fun cancellationCannotBeRevivedByLateSuccess() {
        val (id, offer) = offerFile()
        accept(offer)
        onMain { manager.cancelTransfer(id) }
        success(offer)
        assertEquals(TransferStatus.CANCELLED, repository.queue.items.value.single().status)
    }

    @Test fun textTaskAcceptanceIsNotDeliveryAndSuccessRecordsActualBytes() {
        onMain { manager.sendText("Synthetic text 😀") }
        val payload = fake.sent.single()
        assertEquals(TransferStatus.RUNNING, repository.queue.items.value.single().status)
        val size = payload.asBytes()!!.size.toLong()
        onMain { fake.callback.onPayloadTransferUpdate("peer", PayloadTransferUpdate.Builder().setPayloadId(payload.id)
            .setTotalBytes(size).setBytesTransferred(size).setStatus(PayloadTransferUpdate.Status.SUCCESS).build()) }
        val result = repository.queue.items.value.single()
        assertEquals(TransferStatus.DONE, result.status)
        assertEquals(size, result.bytes)
    }

    private fun receiveText(text: String) {
        val payload = Payload.fromBytes(TransferProtocol.encode(TransferProtocol.Frame("text", UUID.randomUUID().toString(), text = text)))
        val size = payload.asBytes()!!.size.toLong()
        onMain {
            fake.callback.onPayloadReceived("peer", payload)
            fake.callback.onPayloadTransferUpdate("peer", PayloadTransferUpdate.Builder().setPayloadId(payload.id)
                .setTotalBytes(size).setBytesTransferred(size).setStatus(PayloadTransferUpdate.Status.SUCCESS).build())
        }
    }

    @Test fun completedTextsQueueAndRemainCopyableAfterPeerDisconnects() {
        receiveText("First synthetic text"); receiveText("Second synthetic text")
        assertEquals("First synthetic text", manager.incomingText.value!!.text)
        onMain { fake.lifecycle.onDisconnected("peer") }
        assertEquals("First synthetic text", manager.incomingText.value!!.text)
        onMain { manager.dismissText() }
        assertEquals("Second synthetic text", manager.incomingText.value!!.text)
        onMain { manager.dismissText() }
        assertNull(manager.incomingText.value)
    }

    @Test fun dismissingTextDoesNotRejectAnUnrelatedFileOffer() {
        receiveText("Synthetic message")
        val frame = TransferProtocol.Frame("offer", UUID.randomUUID().toString(), 71,
            name = "expected.txt", size = 12, mime = "text/plain")
        onMain { fake.callback.onPayloadReceived("peer", Payload.fromBytes(TransferProtocol.encode(frame))) }
        assertEquals(frame.id, manager.incomingFile.value!!.transferId)
        onMain { manager.dismissText() }
        assertEquals(frame.id, manager.incomingFile.value!!.transferId)
        assertEquals(TransferStatus.WAITING, repository.queue.items.value.last().status)
    }

    @Test fun startupFailuresNeverPretendToBeVisibleOrScanning() {
        onMain { manager.disconnect(); fake.failStarts = true; manager.startAdvertising() }
        assertEquals(ConnState.IDLE, manager.connState.value)
        assertTrue(manager.error.value!!.startsWith("Cannot make this device visible"))
        onMain { manager.startDiscovery() }
        assertEquals(ConnState.IDLE, manager.connState.value)
        assertTrue(manager.error.value!!.startsWith("Cannot scan"))
    }
}
