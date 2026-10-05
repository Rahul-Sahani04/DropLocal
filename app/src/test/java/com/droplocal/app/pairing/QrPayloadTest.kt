package com.droplocal.app.pairing

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class QrPayloadTest {
    private val now = 1_800_000_000L
    private fun payload(device: String = "Pixel") = QrPayload(
        session = "a81cbe66-baf1-4fe0-975b-f498792a060c", device = device, expires = now + 120,
    )

    @Test fun escapedUnicodeNamesRoundTrip() {
        val p = payload("Pixel, \"旅行\" \\ phone")
        assertEquals(p, QrPayload.parse(p.toJson(), now))
        assertEquals(p.device, JSONObject(p.toJson()).getString("device"))
    }

    @Test fun generatedSessionsAreFullUniqueUuids() {
        val sessions = (1..50).map { QrPayload(device = "Android").session }
        assertEquals(50, sessions.toSet().size)
        sessions.forEach { assertEquals(it, UUID.fromString(it).toString()) }
    }

    @Test fun expiryIsExclusiveAndBounded() {
        assertNotNull(QrPayload.parse(payload().copy(expires = now + 1).toJson(), now))
        assertNull(QrPayload.parse(payload().copy(expires = now).toJson(), now))
        assertNull(QrPayload.parse(payload().copy(expires = now - 1).toJson(), now))
        assertNull(QrPayload.parse(payload().copy(expires = now + 121).toJson(), now))
        assertTrue(payload().isExpired(now + 120))
    }

    @Test fun rejectsForeignVersionAndInvalidSessions() {
        assertNull(QrPayload.parse(payload().copy(app = "other").toJson(), now))
        assertNull(QrPayload.parse(payload().copy(v = 2).toJson(), now))
        listOf("", "abc12345", "a81cbe66-baf1-4fe0-975b-f498792a060", "not-a-uuid").forEach {
            assertNull(QrPayload.parse(payload().copy(session = it).toJson(), now))
        }
    }

    @Test fun rejectsMalformedAndPermissiveJsonExtensions() {
        val valid = payload().toJson()
        listOf("", "[]", "{}", "null", valid + "x", valid.dropLast(1),
            "\u000c" + valid,
            valid.replace("\"v\"", "v"), valid.replace('"', '\''),
            valid.replace("\"v\":1", "\"v\":1.0"),
            valid.replace("\"v\":1", "\"v\":\"1\""),
            valid.dropLast(1) + ",}",
            valid.dropLast(1) + ",\"v\":1}",
            valid.dropLast(1) + ",\"extra\":1}",
        ).forEach { assertNull(it, QrPayload.parse(it, now)) }
    }

    @Test fun rejectsMissingOrWrongTypedFieldsAndOversizeNames() {
        val missing = JSONObject(payload().toJson()).apply { remove("device") }.toString()
        assertNull(QrPayload.parse(missing, now))
        assertNull(QrPayload.parse(JSONObject(payload().toJson()).put("device", 4).toString(), now))
        listOf("", " ", "x".repeat(129), "bad\nname").forEach {
            assertNull(QrPayload.parse(payload(it).toJson(), now))
        }
        assertNull(QrPayload.parse(" ".repeat(4097), now))
    }

    @Test fun rejectsOverflowNumbersAndLongStringsWithoutCrashing() {
        val valid = payload().toJson()
        assertNull(QrPayload.parse(valid.replace("\"v\":1", "\"v\":18446744073709551617"), now))
        assertNull(QrPayload.parse(valid.replace((now + 120).toString(), "18446744075509551736"), now))
        assertNull(QrPayload.parse(payload("x".repeat(3500)).toJson(), now))
    }
}
