package com.driveplayer.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * DataStore-backed list of recent search queries, partitioned by [Namespace] so
 * the local-library search and the Drive-cloud search keep separate histories.
 *
 * Why two namespaces and not one combined list:
 *  - Mental models differ: a query the user typed on the Cloud tab is rarely
 *    relevant on the Local tab and vice-versa.
 *  - Privacy: showing your "Drive vacation 2023" search on the Local tab would
 *    surprise the user.
 *
 * Capped at [MAX_ENTRIES] with newest-first ordering. Adding an existing query
 * promotes it to the top instead of duplicating.
 */
class RecentSearchStore(private val context: Context) {

    enum class Namespace(val key: String) {
        LOCAL("local"),
        CLOUD("cloud"),
    }

    fun recents(ns: Namespace): Flow<List<String>> =
        context.recentSearchDataStore.data.map { prefs ->
            prefs[keyFor(ns)]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
                ?: emptyList()
        }

    suspend fun record(ns: Namespace, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.recentSearchDataStore.edit { prefs ->
            val current = prefs[keyFor(ns)]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
                ?: emptyList()
            // Case-insensitive de-dupe; preserve the user's most recent casing.
            val deduped = current.filterNot { it.equals(trimmed, ignoreCase = true) }
            val updated = (listOf(trimmed) + deduped).take(MAX_ENTRIES)
            prefs[keyFor(ns)] = Json.encodeToString(updated)
        }
    }

    suspend fun clear(ns: Namespace) {
        context.recentSearchDataStore.edit { prefs ->
            prefs.remove(keyFor(ns))
        }
    }

    suspend fun remove(ns: Namespace, query: String) {
        context.recentSearchDataStore.edit { prefs ->
            val current = prefs[keyFor(ns)]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }
                ?: return@edit
            val updated = current.filterNot { it.equals(query, ignoreCase = true) }
            prefs[keyFor(ns)] = Json.encodeToString(updated)
        }
    }

    companion object {
        const val MAX_ENTRIES = 8
        private fun keyFor(ns: Namespace) = stringPreferencesKey("recents_${ns.key}")
    }
}

private val Context.recentSearchDataStore by preferencesDataStore(name = "recent_searches")
