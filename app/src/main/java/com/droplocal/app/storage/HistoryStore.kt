package com.droplocal.app.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("droplocal")

/** Last-10 local transfers, contents never stored (PRD §18). */
class HistoryStore(private val context: Context) {
    private val key = stringPreferencesKey("history_v1")
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    fun clearError() { _error.value = null }

    val history: Flow<List<TransferSession>> = context.dataStore.data.catch { exception ->
        if (exception is CancellationException) throw exception
        _error.value = "Could not read local history. Saved files are unaffected."
        emit(emptyPreferences())
    }.map { prefs ->
        HistoryRecords.decode(prefs[key] ?: "[]")
    }

    suspend fun record(s: TransferSession) {
        try {
            context.dataStore.edit { prefs ->
                prefs[key] = HistoryRecords.encode(HistoryRecords.upsert(HistoryRecords.decode(prefs[key] ?: "[]"), s))
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _error.value = "Could not record local history. Saved files are unaffected."; throw e }
    }

    suspend fun clear() { context.dataStore.edit { it.remove(key) } }
}

/** Pure codec used by both loading and writing, including older history_v1 records. */
internal object HistoryRecords {
    fun upsert(records: List<TransferSession>, session: TransferSession): List<TransferSession> =
        (listOf(session) + records).filter { it.id.isNotBlank() }.distinctBy { it.id }.take(10)

    fun decode(raw: String): List<TransferSession> {
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                TransferSession(
                    id = o.getString("id").also { require(it.isNotBlank()) },
                    name = o.optString("name"), size = o.optLong("size", -1L),
                    mime = o.optString("mime", "application/octet-stream"),
                    direction = TransferDirection.valueOf(o.optString("dir", "SENT")),
                    peer = o.optString("peer"), bytes = o.optLong("bytes").coerceAtLeast(0L),
                    status = TransferStatus.valueOf(o.optString("status", "DONE")),
                    startedAt = o.optLong("at"), endedAt = o.optLong("end").takeIf { it > 0 },
                    error = o.nullableString("error"), batchId = o.nullableString("batchId"),
                    outputUri = o.nullableString("outputUri"), isText = o.optBoolean("isText", false),
                    batchIndex = o.optInt("batchIndex", 1), batchCount = o.optInt("batchCount", 1),
                    batchSize = o.optLong("batchSize", -1),
                )
            } catch (_: Exception) { null }
        }.distinctBy { it.id }.take(10)
    }

    fun encode(records: List<TransferSession>): String {
        val arr = JSONArray()
        records.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(10).forEach { s ->
            arr.put(JSONObject()
                .put("id", s.id).put("name", s.name).put("size", s.size)
                .put("mime", s.mime).put("dir", s.direction.name)
                .put("peer", s.peer).put("bytes", s.bytes)
                .put("status", s.status.name).put("at", s.startedAt)
                .put("end", s.endedAt ?: 0L).put("error", s.error ?: JSONObject.NULL)
                .put("batchId", s.batchId ?: JSONObject.NULL)
                .put("outputUri", s.outputUri ?: JSONObject.NULL).put("isText", s.isText)
                .put("batchIndex", s.batchIndex).put("batchCount", s.batchCount).put("batchSize", s.batchSize))
        }
        return arr.toString()
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
