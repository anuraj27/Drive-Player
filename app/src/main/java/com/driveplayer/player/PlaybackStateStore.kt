package com.driveplayer.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-file "Save media settings" store, keyed on the same `positionKey` we
 * use for the simple position resume — Drive `fileId` for cloud videos and
 * `local_<contentUri>` / `download_<fileId>` for local + downloads.
 *
 * What we persist (mirrors VLC for Android's medialibrary.db `MediaWrapper`):
 *  - position
 *  - audio-track id
 *  - subtitle-track id (or external `.srt` URI, mutually exclusive)
 *  - subtitle delay
 *  - audio delay
 *  - playback rate
 *  - resize mode (FIT / FILL / ZOOM)
 *  - manual zoom scale + pan offsets
 *
 * Why a separate store from [PlaybackPositionStore]: position is hot-written
 * every five seconds during playback; the rest of the state is captured only
 * on pause / release. Splitting the writes lets the position writer stay on
 * fast SharedPreferences while the richer state uses DataStore + JSON without
 * either path bottlenecking the other. The two are queried together at
 * [com.driveplayer.ui.player.controllers.PlayerController.applyRestoredState].
 *
 * All entries live in a single JSON blob keyed by `STATES_KEY`. `Map<String,
 * State>` keeps the lookup O(1) regardless of how many videos a user has
 * watched, and serialising the whole map per write is cheap (a few KB at
 * most for thousands of entries).
 */
@Serializable
data class PlaybackState(
    /** libVLC track ID, or -2 for "no preference" (default), -1 for "user disabled". */
    val audioTrackId: Int = -2,
    /** Same convention as [audioTrackId]. */
    val subtitleTrackId: Int = -2,
    /** URI string of a user-attached external `.srt`. Best-effort: content:// URIs
     *  may not survive a process restart unless permission was persisted. */
    val externalSubtitleUri: String? = null,
    /** Microseconds — matches libVLC's `audioDelay` / `spuDelay` API directly. */
    val subtitleDelayUs: Long = 0L,
    val audioDelayUs: Long = 0L,
    val playbackRate: Float = 1f,
)

private val Context.playbackStateDataStore by preferencesDataStore(name = "playback_state")
private val STATES_KEY = stringPreferencesKey("states_json")

class PlaybackStateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(key: String): PlaybackState? {
        val map = readMap()
        return map[key]
    }

    suspend fun save(key: String, state: PlaybackState) {
        context.playbackStateDataStore.edit { prefs ->
            val current = prefs[STATES_KEY]
                ?.let { runCatching { json.decodeFromString<Map<String, PlaybackState>>(it) }.getOrNull() }
                ?: emptyMap()
            prefs[STATES_KEY] = json.encodeToString(current + (key to state))
        }
    }

    suspend fun clear(key: String) {
        context.playbackStateDataStore.edit { prefs ->
            val current = prefs[STATES_KEY]
                ?.let { runCatching { json.decodeFromString<Map<String, PlaybackState>>(it) }.getOrNull() }
                ?: return@edit
            prefs[STATES_KEY] = json.encodeToString(current - key)
        }
    }

    private suspend fun readMap(): Map<String, PlaybackState> {
        val prefs = context.playbackStateDataStore.data.first()
        val raw = prefs[STATES_KEY] ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, PlaybackState>>(raw) }.getOrDefault(emptyMap())
    }
}
