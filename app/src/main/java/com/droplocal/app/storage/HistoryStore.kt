package com.droplocal.app.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.droplocal.app.transfer.TransferDirection
import com.droplocal.app.transfer.TransferSession
import com.droplocal.app.transfer.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("droplocal")

/** Last-10 local transfers, contents never stored (PRD §18). */
class HistoryStore(private val context: Context) {
    private val key = stringPreferencesKey("history_v1")

    val history: Flow<List<TransferSession>> = context.dataStore.data.map { prefs ->
        val raw = prefs[key] ?: "[]"
        try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TransferSession(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    size = o.optLong("size"),
                    mime = o.optString("mime"),
                    direction = TransferDirection.valueOf(o.optString("dir", "SENT")),
                    peer = o.optString("peer"),
                    bytes = o.optLong("bytes"),
                    status = TransferStatus.valueOf(o.optString("status", "DONE")),
                    startedAt = o.optLong("at"),
                    endedAt = o.optLong("end").takeIf { it > 0 },
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun record(s: TransferSession) {
        context.dataStore.edit { prefs ->
            val arr = try { JSONArray(prefs[key] ?: "[]") } catch (_: Exception) { JSONArray() }
            val o = JSONObject()
                .put("id", s.id).put("name", s.name).put("size", s.size)
                .put("mime", s.mime).put("dir", s.direction.name)
                .put("peer", s.peer).put("bytes", s.bytes)
                .put("status", s.status.name).put("at", s.startedAt)
                .put("end", s.endedAt ?: 0L)
            val next = JSONArray().put(o)
            for (i in 0 until minOf(arr.length(), 9)) next.put(arr.get(i))
            prefs[key] = next.toString()
        }
    }

    suspend fun clear() { context.dataStore.edit { it.remove(key) } }
}
