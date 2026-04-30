package com.driveplayer.player

import android.content.Context
import android.content.SharedPreferences

class PlaybackPositionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)

    fun save(fileId: String, positionMs: Long) {
        if (positionMs > 5_000L) {
            prefs.edit().putLong(fileId, positionMs).apply()
        }
    }

    fun get(fileId: String): Long = prefs.getLong(fileId, 0L)

    fun clear(fileId: String) {
        prefs.edit().remove(fileId).apply()
    }

    /**
     * Bulk snapshot of every persisted position. Used by browse-screen
     * ViewModels to render the embedded "watched-progress" line on each list
     * item without doing N separate `prefs.getLong` calls during recomposition.
     *
     * Reading the whole map is cheap because [SharedPreferences] keeps the
     * file in memory after first load — we're just shallow-copying the
     * existing entries. Filters non-Long values defensively in case some
     * other corner of the codebase ever stuffs a different type into the
     * same prefs file.
     */
    fun allPositions(): Map<String, Long> {
        val raw = prefs.all
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>(raw.size)
        for ((k, v) in raw) {
            if (v is Long) out[k] = v
        }
        return out
    }
}
