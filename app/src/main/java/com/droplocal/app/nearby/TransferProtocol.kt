package com.droplocal.app.nearby

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID
import java.text.Normalizer

/** Versioned, bounded control frames. Nearby payload IDs, not arrival order, identify files. */
internal object TransferProtocol {
    const val VERSION = 1
    const val MAX_CONTROL_BYTES = 4096
    const val MAX_FILE_SIZE = 100L * 1024 * 1024 * 1024
    private val mimePattern = Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*")
    private val kinds = setOf("offer", "accept", "reject", "cancel", "saved", "error", "text")
    private val jsonString = "\"(?:[^\"\\\\\\x00-\\x1F]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*+\""
    private const val ws = "[ \\t\\r\\n]*+"
    private val pair = "$jsonString$ws:$ws(?:$jsonString|-?(?:0|[1-9][0-9]*))"
    private val strictObject = Regex("$ws\\{$ws$pair(?:$ws,$ws$pair)*+$ws\\}$ws")

    data class Frame(
        val type: String,
        val id: String,
        val payloadId: Long = 0,
        val name: String = "",
        val size: Long = -1,
        val mime: String = "",
        val text: String = "",
        val reason: String = "",
        val batchId: String? = null,
        val batchIndex: Int = 1,
        val batchCount: Int = 1,
        val batchSize: Long = -1,
    )

    fun validId(id: String): Boolean = try {
        UUID.fromString(id).toString() == id.lowercase()
    } catch (_: IllegalArgumentException) { false }

    fun validMetadata(name: String, size: Long, mime: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.endsWith('.') &&
            name == Normalizer.normalize(name, Normalizer.Form.NFKC).trim() &&
            name.toByteArray(Charsets.UTF_8).size <= 200 &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() ||
                Character.getType(it) == Character.FORMAT.toInt() } &&
            size in -1..MAX_FILE_SIZE && mime.length <= 127 && mimePattern.matches(mime)

    fun encode(frame: Frame): ByteArray {
        val json = JSONObject().put("v", VERSION).put("type", frame.type).put("id", frame.id)
        if (frame.type == "text") json.put("text", frame.text)
        else {
            json.put("payloadId", frame.payloadId)
            when (frame.type) {
                "offer" -> json.put("name", frame.name).put("size", frame.size).put("mime", frame.mime)
                "saved" -> json.put("size", frame.size)
                "reject", "cancel", "error" -> json.put("reason", frame.reason.take(300))
            }
        }
        if (frame.type == "offer" && frame.batchId != null) {
            json.put("batchId", frame.batchId).put("batchIndex", frame.batchIndex)
                .put("batchCount", frame.batchCount).put("batchSize", frame.batchSize)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, maxBytes: Int): Frame? = try {
        require(bytes.size <= maxBytes)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val raw = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require(strictObject.matches(raw))
        val json = JSONObject(raw)
        require(Regex(pair).findAll(raw).count() == json.length())
        fun string(key: String): String = (json.get(key) as? String) ?: error("Invalid $key")
        fun number(key: String): Long {
            val value = json.get(key)
            require(value is Int || value is Long)
            return (value as Number).toLong()
        }
        require(number("v") == VERSION.toLong())
        val type = string("type")
        val id = string("id")
        require(type in kinds && validId(id))
        val batchFields = if (type == "offer" && json.has("batchId")) setOf("batchId", "batchIndex", "batchCount", "batchSize") else emptySet()
        val expected = setOf("v", "type", "id") + batchFields + when (type) {
            "text" -> setOf("text")
            "offer" -> setOf("payloadId", "name", "size", "mime")
            "saved" -> setOf("payloadId", "size")
            "error", "cancel", "reject" -> setOf("payloadId", "reason")
            else -> setOf("payloadId")
        }
        require(json.keys().asSequence().toSet() == expected)
        if (type == "text") Frame(type, id, text = string("text"))
        else {
            require(bytes.size <= MAX_CONTROL_BYTES)
            val payloadId = number("payloadId") // Nearby IDs are signed longs; zero is legal.
            when (type) {
                "offer" -> {
                    val batchId = if (batchFields.isEmpty()) null else string("batchId").also { require(validId(it)) }
                    val index = if (batchId == null) 1L else number("batchIndex")
                    val count = if (batchId == null) 1L else number("batchCount")
                    val total = if (batchId == null) -1L else number("batchSize")
                    require(count in 1..32 && index in 1..count && total in -1..MAX_FILE_SIZE * count)
                    Frame(type, id, payloadId, string("name"), number("size"), string("mime"),
                        batchId = batchId, batchIndex = index.toInt(), batchCount = count.toInt(), batchSize = total)
                        .also { require(validMetadata(it.name, it.size, it.mime)); require(total == -1L || it.size < 0 || total >= it.size) }
                }
                "saved" -> Frame(type, id, payloadId, size = number("size"))
                    .also { require(it.size in 0..MAX_FILE_SIZE) }
                "reject", "cancel", "error" -> Frame(type, id, payloadId, reason = string("reason"))
                    .also { require(it.reason.length <= 300) }
                else -> Frame(type, id, payloadId)
            }
        }
    } catch (_: Exception) { null }
}

/** A compact advertised QR identity, never an authentication credential. */
internal object EndpointAdvertisement {
    data class Identity(val session: String, val expires: Long, val name: String)

    fun encode(session: String, expires: Long, name: String): String {
        require(TransferProtocol.validId(session))
        var display = name.take(42)
        while (display.toByteArray(Charsets.UTF_8).size > 42) display = display.dropLast(1)
        val encodedName = Base64.getUrlEncoder().withoutPadding().encodeToString(display.toByteArray(Charsets.UTF_8))
        return "DL1|${session.replace("-", "")}|${expires.toString(36)}|$encodedName"
    }

    fun decode(value: String): Identity? = try {
        require(value.toByteArray(Charsets.UTF_8).size <= 131)
        val parts = value.split('|')
        require(parts.size == 4 && parts[0] == "DL1" && Regex("[0-9a-fA-F]{32}").matches(parts[1]))
        val hex = parts[1]
        val session = "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}".lowercase()
        val nameBytes = Base64.getUrlDecoder().decode(parts[3])
        require(nameBytes.size <= 42)
        val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(nameBytes)).toString()
        require(name.none { it.isISOControl() })
        Identity(session, parts[2].toLong(36), name.ifBlank { "Android" })
    } catch (_: Exception) { null }

    fun matches(identity: Identity?, session: String, expires: Long, nowSec: Long): Boolean =
        identity != null && identity.session == session && identity.expires == expires &&
            expires > nowSec && expires <= nowSec + 120
}

/** Pure attempt state: explicit consent, immutable identity, and terminal callbacks cannot regress. */
internal class TransferAttempt(
    val id: String,
    val payloadId: Long,
    val generation: Long,
    val outgoing: Boolean,
) {
    enum class Phase { OFFERED, ACCEPTED, TRANSFERRING, SAVING, AWAITING_SAVE, TERMINAL }
    var phase = Phase.OFFERED
        private set
    var delivered = false
        private set
    var saved = false
        private set
    fun matches(id: String, payloadId: Long, generation: Long): Boolean =
        this.id == id && this.payloadId == payloadId && this.generation == generation && phase != Phase.TERMINAL
    fun accept(): Boolean {
        if (phase != Phase.OFFERED) return false
        phase = Phase.ACCEPTED
        return true
    }
    fun start(): Boolean {
        if (phase != Phase.ACCEPTED) return false
        phase = Phase.TRANSFERRING
        return true
    }
    fun delivery(): Boolean {
        if (phase != Phase.ACCEPTED && phase != Phase.TRANSFERRING) return false
        delivered = true
        phase = if (outgoing) Phase.AWAITING_SAVE else Phase.SAVING
        return true
    }
    fun acknowledgeSave(): Boolean {
        if (!outgoing || phase == Phase.OFFERED || phase == Phase.TERMINAL) return false
        saved = true
        return true
    }
    fun finish(): Boolean {
        if (phase == Phase.TERMINAL) return false
        phase = Phase.TERMINAL
        return true
    }
}

internal class SingleActiveQueue<T> {
    private val pending = ArrayDeque<T>()
    var active: T? = null
        private set
    fun enqueue(value: T) { if (value != active && value !in pending) pending.addLast(value) }
    fun next(): T? {
        if (active == null && pending.isNotEmpty()) active = pending.removeFirst()
        return active
    }
    fun finish(value: T) {
        pending.remove(value)
        if (active == value) active = null
    }
    fun snapshot(): List<T> = listOfNotNull(active) + pending.toList()
    fun clear() { active = null; pending.clear() }
}

/** Real manager index, shared by tests: two identities are registered/removed atomically. */
internal class TransferAttemptIndex<T>(private val state: (T) -> TransferAttempt) {
    private val ids = mutableMapOf<String, T>()
    private val payloads = mutableMapOf<Long, String>()
    val values: Collection<T> get() = ids.values
    operator fun get(id: String): T? = ids[id]
    fun containsPayload(payloadId: Long): Boolean = payloadId in payloads
    fun put(value: T): Boolean {
        val attempt = state(value)
        if (attempt.id in ids || attempt.payloadId in payloads || attempt.phase == TransferAttempt.Phase.TERMINAL) return false
        ids[attempt.id] = value
        payloads[attempt.payloadId] = attempt.id
        return true
    }
    fun payload(payloadId: Long, generation: Long): T? {
        val value = payloads[payloadId]?.let(ids::get) ?: return null
        val attempt = state(value)
        return value.takeIf { attempt.matches(attempt.id, payloadId, generation) }
    }
    fun remove(id: String): T? {
        val value = ids.remove(id) ?: return null
        payloads.remove(state(value).payloadId)
        return value
    }
    fun clear() { ids.clear(); payloads.clear() }
}
