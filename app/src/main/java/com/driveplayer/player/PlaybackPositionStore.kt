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
}
