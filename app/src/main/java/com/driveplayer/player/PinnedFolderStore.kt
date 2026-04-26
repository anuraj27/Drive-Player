package com.driveplayer.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.driveplayer.data.model.DriveFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PinnedFolder(val id: String, val name: String)

private val Context.pinnedDataStore by preferencesDataStore(name = "pinned_folders")
private val PINNED_KEY = stringPreferencesKey("folders_json")

class PinnedFolderStore(private val context: Context) {

    val pinnedFolders: Flow<List<PinnedFolder>> = context.pinnedDataStore.data.map { prefs ->
        prefs[PINNED_KEY]
            ?.let { runCatching { Json.decodeFromString<List<PinnedFolder>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun pin(folder: DriveFile) {
        context.pinnedDataStore.edit { prefs ->
            val current = prefs[PINNED_KEY]
                ?.let { runCatching { Json.decodeFromString<List<PinnedFolder>>(it) }.getOrNull() }
                ?: emptyList()
            // Deduplicate by id, append new pin, cap at 20.
            val updated = (current.filterNot { it.id == folder.id } + PinnedFolder(folder.id, folder.name)).take(20)
            prefs[PINNED_KEY] = Json.encodeToString(updated)
        }
    }

    suspend fun unpin(folderId: String) {
        context.pinnedDataStore.edit { prefs ->
            val current = prefs[PINNED_KEY]
                ?.let { runCatching { Json.decodeFromString<List<PinnedFolder>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[PINNED_KEY] = Json.encodeToString(current.filterNot { it.id == folderId })
        }
    }
}
