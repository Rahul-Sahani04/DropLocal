package com.droplocal.app.pairing

import org.junit.Assert.*
import org.junit.Test

class QrPayloadTest {
    @Test fun roundTrip() {
        val p = QrPayload(session = "abc12345", device = "Pixel", expires = 9_999_999_999L)
        val parsed = QrPayload.parse(p.toJson())
        assertNotNull(parsed)
        assertEquals("abc12345", parsed!!.session)
        assertEquals("droplocal", parsed.app)
        assertFalse(parsed.isExpired())
    }

    @Test fun rejectsForeignApp() {
        assertNull(QrPayload.parse("""{"v":1,"app":"other","session":"x","device":"d","expires":9999999999}"""))
    }

    @Test fun detectsExpiry() {
        val p = QrPayload(session = "s", expires = 1L)
        assertTrue(p.isExpired(nowSec = 2L))
    }

    @Test fun codeFormat() {
        repeat(50) {
            val c = SessionCode.new()
            assertTrue(c.matches(Regex("\\d{3} \\d{3}")))
        }
    }
}
