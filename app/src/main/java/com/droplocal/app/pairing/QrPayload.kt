package com.droplocal.app.pairing

import org.json.JSONObject
import java.util.UUID

/** Receiver advertisement shortcut, never a substitute for Nearby authentication. */
data class QrPayload(
    val v: Int = 1,
    val app: String = "droplocal",
    val session: String = UUID.randomUUID().toString(),
    val device: String = android.os.Build.MODEL ?: "Android",
    val expires: Long = System.currentTimeMillis() / 1000 + MAX_TTL_SECONDS,
) {
    fun toJson(): String = JSONObject().put("v", v).put("app", app)
        .put("session", session).put("device", device).put("expires", expires).toString()

    fun isExpired(nowSec: Long = System.currentTimeMillis() / 1000): Boolean = nowSec >= expires

    companion object {
        const val MAX_TTL_SECONDS = 120L
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val jsonString = "\"(?:[^\"\\\\\\x00-\\x1F]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*+\""
        private const val ws = "[ \\t\\r\\n]*+"
        private val pair = "$jsonString$ws:$ws(?:$jsonString|-?(?:0|[1-9][0-9]*))"
        // Android's JSON parser accepts non-JSON extensions. Gate the small flat schema first.
        private val strictObject = Regex("$ws\\{$ws$pair(?:$ws,$ws$pair)*+$ws\\}$ws")
        private val fields = setOf("v", "app", "session", "device", "expires")

        fun parse(json: String, nowSec: Long = System.currentTimeMillis() / 1000): QrPayload? {
            if (json.length > 4096 || !strictObject.matches(json) || nowSec < 0) return null
            return try {
                val obj = JSONObject(json)
                if (obj.keys().asSequence().toSet() != fields || obj.length() != 5) return null
                // Duplicate keys are not accepted, including escaped spellings of the same key.
                val pairs = Regex(pair).findAll(json).toList()
                if (pairs.size != 5) return null
                val version = obj.get("v")
                val expiry = obj.get("expires")
                if (version !is Number || version.toString() != "1" || expiry !is Number) return null
                if (obj.get("app") != "droplocal") return null
                val session = obj.get("session") as? String ?: return null
                val device = obj.get("device") as? String ?: return null
                if (!uuid.matches(session) || UUID.fromString(session).toString() != session.lowercase()) return null
                if (device.isBlank() || device.length > 128 || device.any { it.code < 32 }) return null
                val expires = expiry.toString().toLongOrNull() ?: return null
                if (expires <= nowSec || expires - nowSec > MAX_TTL_SECONDS) return null
                QrPayload(session = session, device = device, expires = expires)
            } catch (_: Exception) { null }
        }
    }
}
