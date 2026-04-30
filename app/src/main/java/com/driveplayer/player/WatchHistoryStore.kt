package com.driveplayer.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchEntry(
    val fileId: String,
    val title: String,
    val mimeType: String,
    val thumbnailLink: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedAt: Long,
    /**
     * The Drive folder this video was watched from, when known. Saved on the player side
     * so reopening from the Continue-Watching carousel can re-fetch siblings (and pick up
     * an external `.srt` automatically). Old entries without a parent simply fall back to
     * the no-siblings path. Defaulted for backwards compatibility with stored JSON.
     */
    val parentFolderId: String? = null,
)

private val Context.watchDataStore by preferencesDataStore(name = "watch_history")
private val WATCH_KEY = stringPreferencesKey("entries_json")

class WatchHistoryStore(private val context: Context) {

    fun recentlyWatched(): Flow<List<WatchEntry>> = context.watchDataStore.data.map { prefs ->
        prefs[WATCH_KEY]
            ?.let { runCatching { Json.decodeFromString<List<WatchEntry>>(it) }.getOrNull() }
            ?.sortedByDescending { it.lastWatchedAt }
            ?: emptyList()
    }

    suspend fun save(entry: WatchEntry) {
        context.watchDataStore.edit { prefs ->
            val current = prefs[WATCH_KEY]
                ?.let { runCatching { Json.decodeFromString<List<WatchEntry>>(it) }.getOrNull() }
                ?: emptyList()
            // Upsert by fileId, keep newest at front, cap at 20 entries.
            val updated = (listOf(entry) + current.filterNot { it.fileId == entry.fileId }).take(20)
            prefs[WATCH_KEY] = Json.encodeToString(updated)
        }
    }

    suspend fun clear(fileId: String) {
        context.watchDataStore.edit { prefs ->
            val current = prefs[WATCH_KEY]
                ?.let { runCatching { Json.decodeFromString<List<WatchEntry>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[WATCH_KEY] = Json.encodeToString(current.filterNot { it.fileId == fileId })
        }
    }
}
