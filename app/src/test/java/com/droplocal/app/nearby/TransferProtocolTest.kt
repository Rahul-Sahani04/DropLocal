package com.droplocal.app.nearby

import org.junit.Assert.*
import org.junit.Test

class TransferProtocolTest {
    private val id = "f2000000-0000-4000-8000-000000000001"
    private val second = "f2000000-0000-4000-8000-000000000002"
    private val maxBytes = 32_768
    private fun decode(raw: String) = TransferProtocol.decode(raw.toByteArray(Charsets.UTF_8), maxBytes)
    private fun offer(payload: Long = 22) = TransferProtocol.Frame("offer", id, payload,
        name = "a, quoted \"file\".pdf", size = 123, mime = "application/pdf")

    @Test fun offerRoundTripsAndIncludesActualPayloadId() {
        val frame = offer(Long.MIN_VALUE)
        assertEquals(frame, TransferProtocol.decode(TransferProtocol.encode(frame), maxBytes))
    }

    @Test fun batchOfferRoundTripsAndRejectsInvalidCountsAndTotals() {
        val frame = offer().copy(batchId = second, batchIndex = 2, batchCount = 3, batchSize = 1000)
        assertEquals(frame, TransferProtocol.decode(TransferProtocol.encode(frame), maxBytes))
        for (invalid in listOf(frame.copy(batchCount = 0), frame.copy(batchIndex = 4),
            frame.copy(batchCount = 33), frame.copy(batchSize = 12), frame.copy(batchId = "bad"))) {
            assertNull(TransferProtocol.decode(TransferProtocol.encode(invalid), maxBytes))
        }
    }

    @Test fun rejectsUnsupportedVersionsAndWrongTypes() {
        val raw = String(TransferProtocol.encode(offer()), Charsets.UTF_8)
        assertNull(decode(raw.replace("\"v\":1", "\"v\":2")))
        assertNull(decode(raw.replace("\"v\":1", "\"v\":\"1\"")))
        assertNull(decode(raw.replace("\"size\":123", "\"size\":123.0")))
        assertNull(decode(raw.replace("\"size\":123", "\"size\":\"123\"")))
    }

    @Test fun rejectsLenientJsonDuplicateKeysAndUnknownFields() {
        assertNull(decode("{v:1,type:'accept',id:'$id',payloadId:1}"))
        assertNull(decode("{\"v\":1,\"v\":1,\"type\":\"accept\",\"id\":\"$id\",\"payloadId\":1}"))
        assertNull(decode("{\"v\":1,\"type\":\"accept\",\"id\":\"$id\",\"payloadId\":1,\"extra\":3}"))
        assertNull(decode("{\"v\":1,\"type\":\"accept\",\"id\":\"$id\"}"))
    }

    @Test fun rejectsTraversalControlsNormalizationAndFilenameByteLimits() {
        for (name in listOf("", "..", "../evil", "a/b", "a\\b", "a:b", "a\u0000b", "x\u202Ey", "a.", "／evil", " a ", "é".repeat(101))) {
            assertFalse(name, TransferProtocol.validMetadata(name, 0, "text/plain"))
            assertNull(TransferProtocol.decode(TransferProtocol.encode(offer().copy(name = name)), maxBytes))
        }
    }

    @Test fun validatesUnknownAndZeroSizesAndBounds() {
        assertTrue(TransferProtocol.validMetadata("empty", 0, "application/octet-stream"))
        assertTrue(TransferProtocol.validMetadata("unknown", -1, "text/plain"))
        assertFalse(TransferProtocol.validMetadata("negative", -2, "text/plain"))
        assertFalse(TransferProtocol.validMetadata("huge", Long.MAX_VALUE, "text/plain"))
        assertFalse(TransferProtocol.validMetadata("bad", 1, "../mime"))
        assertFalse(TransferProtocol.validMetadata("bad", 1, "text/plain\n"))
    }

    @Test fun rejectsNonUniqueOrMalformedWireIdentities() {
        for (bad in listOf("", "1", "f2000000", "../foo", "1-1-1-1-1")) {
            assertNull(TransferProtocol.decode(TransferProtocol.encode(offer().copy(id = bad)), maxBytes))
        }
    }

    @Test fun encodedUtf8AndJsonEscapingCountTowardsLimit() {
        val frame = TransferProtocol.Frame("text", id, text = "😀\n\"".repeat(100))
        val bytes = TransferProtocol.encode(frame)
        assertTrue(bytes.size > frame.text.length)
        assertNull(TransferProtocol.decode(bytes, bytes.size - 1))
        assertEquals(frame, TransferProtocol.decode(bytes, bytes.size))
    }

    @Test fun rejectsMalformedUtf8() {
        assertNull(TransferProtocol.decode(byteArrayOf(0xc3.toByte(), 0x28), maxBytes))
    }

    @Test fun allTerminalControlFramesRoundTrip() {
        for (type in listOf("accept", "reject", "cancel", "error", "saved")) {
            val frame = TransferProtocol.Frame(type, id, 99,
                reason = if (type in listOf("reject", "cancel", "error")) "No thanks" else "",
                size = if (type == "saved") 17 else -1)
            assertEquals(frame, TransferProtocol.decode(TransferProtocol.encode(frame), maxBytes))
        }
    }

    @Test fun senderCannotTransmitUntilExplicitAcceptance() {
        val attempt = TransferAttempt(id, 9, 3, outgoing = true)
        assertFalse(attempt.start())
        assertFalse(attempt.delivery())
        assertFalse(attempt.acknowledgeSave())
        assertTrue(attempt.accept())
        assertFalse(attempt.accept())
        assertTrue(attempt.start())
        assertTrue(attempt.delivery())
        assertFalse(attempt.saved)
        assertTrue(attempt.acknowledgeSave())
        assertTrue(attempt.saved && attempt.delivered)
    }

    @Test fun receiverRequiresConsentAndDeliveryBeforeSaving() {
        val attempt = TransferAttempt(id, 9, 3, outgoing = false)
        assertFalse(attempt.delivery())
        assertTrue(attempt.accept())
        // SUCCESS may arrive before the FILE callback; identity/consent remain sufficient.
        assertTrue(attempt.delivery())
        assertEquals(TransferAttempt.Phase.SAVING, attempt.phase)
        assertFalse(attempt.acknowledgeSave())
    }

    @Test fun lateProgressAcceptanceSuccessAndTerminalCallbacksCannotReviveCancelledAttempt() {
        val attempt = TransferAttempt(id, 9, 3, outgoing = true)
        assertTrue(attempt.finish())
        assertFalse(attempt.finish())
        assertFalse(attempt.accept())
        assertFalse(attempt.start())
        assertFalse(attempt.delivery())
        assertFalse(attempt.acknowledgeSave())
        assertFalse(attempt.matches(id, 9, 3))
        assertEquals(TransferAttempt.Phase.TERMINAL, attempt.phase)
    }

    @Test fun payloadMappingNeverUsesFifoAndOldRetryOrGenerationCannotMatch() {
        val first = TransferAttempt(id, 900, 3, outgoing = false)
        val other = TransferAttempt(second, 800, 3, outgoing = false)
        val index = TransferAttemptIndex<TransferAttempt> { it }
        assertTrue(index.put(first))
        assertTrue(index.put(other))
        assertFalse(index.put(TransferAttempt(second, 777, 3, false)))
        assertFalse(index.put(TransferAttempt("f2000000-0000-4000-8000-000000000003", 900, 3, false)))
        assertSame(other, index.payload(800, 3))
        assertNull(index.payload(777, 3))
        assertNull(index.payload(900, 4))
        assertFalse(first.matches(id, 900, 4))
        assertFalse(first.matches(id, 901, 3))
        assertFalse(first.matches(second, 900, 3))
        first.finish()
        assertNull(index.payload(900, 3))
        index.remove(first.id)
        assertFalse(index.containsPayload(900))
        val retry = TransferAttempt("f2000000-0000-4000-8000-000000000003", 901, 3, outgoing = true)
        assertTrue(index.put(retry))
        assertSame(retry, index.payload(901, 3))
        assertFalse(retry.matches(id, 900, 3))
        assertTrue(retry.matches(retry.id, 901, 3))
    }

    @Test fun saveAcknowledgementMayPrecedeLocalDeliveryButDoesNotReplaceIt() {
        val attempt = TransferAttempt(id, 9, 3, outgoing = true)
        attempt.accept(); attempt.start()
        assertTrue(attempt.acknowledgeSave())
        assertFalse(attempt.delivered)
        assertTrue(attempt.delivery())
        assertTrue(attempt.saved && attempt.delivered)
    }

    @Test fun senderQueueDoesNotAdvanceForDispatchConsentOrTransmissionOnly() {
        val queue = SingleActiveQueue<String>()
        queue.enqueue(id); queue.enqueue(second)
        assertEquals(id, queue.next())
        assertEquals(id, queue.next())
        assertEquals(listOf(id, second), queue.snapshot())
        queue.finish(id) // Only the manager's terminal settlement calls finish.
        assertEquals(second, queue.next())
        queue.finish(second)
        assertNull(queue.next())
    }

    @Test fun receiveOffersKeepFirstDialogUntilItsDecisionAndCancellationRemovesQueuedOffer() {
        val queue = SingleActiveQueue<String>()
        queue.enqueue(id)
        assertEquals(id, queue.next())
        queue.enqueue(second)
        assertEquals(id, queue.next())
        queue.finish(second)
        assertEquals(id, queue.next())
        queue.finish(id)
        assertNull(queue.next())
    }

    @Test fun advertisementRoundTripsCompactUuidAndUnicodeNameWithinSdkLimit() {
        val name = "Téléphone, \"quoted\" 😀".repeat(5)
        val encoded = EndpointAdvertisement.encode(id, 1120, name)
        assertTrue(encoded.toByteArray().size <= 131)
        val decoded = EndpointAdvertisement.decode(encoded)!!
        assertEquals(id, decoded.session)
        assertEquals(1120L, decoded.expires)
        assertTrue(decoded.name.toByteArray().size <= 42)
    }

    @Test fun qrMatchesExactSessionAndExpiryNotSameDeviceName() {
        val first = EndpointAdvertisement.Identity(id, 1120, "Same phone")
        val secondPhone = first.copy(session = second)
        assertTrue(EndpointAdvertisement.matches(first, id, 1120, 1000))
        assertFalse(EndpointAdvertisement.matches(secondPhone, id, 1120, 1000))
        assertFalse(EndpointAdvertisement.matches(first, id, 1121, 1000))
        assertFalse(EndpointAdvertisement.matches(first, id, 1120, 1120))
        assertFalse(EndpointAdvertisement.matches(first, id, 1120, 999))
        assertNull(EndpointAdvertisement.decode("Same phone"))
    }
}
