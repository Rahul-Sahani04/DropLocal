package com.droplocal.app.pairing

import java.util.UUID
import kotlin.random.Random

/** PRD §7 + §13: short-lived app-level pairing session + Nearby auth digits. */
data class QrPayload(
    val v: Int = 1,
    val app: String = "droplocal",
    val session: String = UUID.randomUUID().toString().take(8),
    val device: String = android.os.Build.MODEL ?: "Android",
    val expires: Long = (System.currentTimeMillis() / 1000) + 120,
) {
    fun toJson(): String =
        """{"v":$v,"app":"$app","session":"$session","device":"$device","expires":$expires}"""

    fun isExpired(nowSec: Long = System.currentTimeMillis() / 1000): Boolean = nowSec > expires

    companion object {
        fun parse(json: String): QrPayload? {
            return try {
            val map = json.trim().removePrefix("{").removeSuffix("}")
                .split(",").associate {
                    val (k, v) = it.split(":", limit = 2)
                    k.trim().trim('"') to v.trim().trim('"')
                }
            if (map["app"] != "droplocal") null
            else QrPayload(
                v = map["v"]?.toIntOrNull() ?: 1,
                session = map["session"] ?: return null,
                device = map["device"] ?: "Android",
                expires = map["expires"]?.toLongOrNull() ?: 0L,
            )
            } catch (_: Exception) { null }
        }
    }
}

object SessionCode {
    /** 6-digit human code shown on both sides (Nearby auth token surrogate for QR flow). */
    fun new(): String {
        val n = Random.nextInt(0, 1_000_000)
        val s = n.toString().padStart(6, '0')
        return "${s.substring(0, 3)} ${s.substring(3)}"
    }
}
