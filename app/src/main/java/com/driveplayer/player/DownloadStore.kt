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
enum class DownloadStatus { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class DownloadEntry(
    val fileId: String,
    val title: String,
    val mimeType: String,
    val downloadManagerId: Long,
    val localPath: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val enqueuedAt: Long,
    // Stored so the user can retry without re-authenticating.
    // OAuth drive.readonly token — short-lived, but sufficient for a retry attempt.
    val accessToken: String? = null,
    // Bytes received before failure/cancellation — persisted so the UI can show "X of Y downloaded".
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    // Wall-clock millis when the entry transitioned into COMPLETED. Used by the
    // auto-cleanup pass in DownloadService.onCreate. `null` for entries that
    // predate this field — they're skipped by cleanup until they finish again.
    val completedAt: Long? = null,
)

private val Context.downloadDataStore by preferencesDataStore(name = "downloads")
private val DOWNLOAD_KEY = stringPreferencesKey("entries_json")

class DownloadStore(private val context: Context) {

    val downloads: Flow<List<DownloadEntry>> = context.downloadDataStore.data.map { prefs ->
        prefs[DOWNLOAD_KEY]
            ?.let { runCatching { Json.decodeFromString<List<DownloadEntry>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun save(entry: DownloadEntry) {
        context.downloadDataStore.edit { prefs ->
            val current = prefs[DOWNLOAD_KEY]
                ?.let { runCatching { Json.decodeFromString<List<DownloadEntry>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = listOf(entry) + current.filterNot { it.fileId == entry.fileId }
            prefs[DOWNLOAD_KEY] = Json.encodeToString(updated)
        }
    }

    suspend fun update(fileId: String, block: (DownloadEntry) -> DownloadEntry) {
        context.downloadDataStore.edit { prefs ->
            val current = prefs[DOWNLOAD_KEY]
                ?.let { runCatching { Json.decodeFromString<List<DownloadEntry>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[DOWNLOAD_KEY] = Json.encodeToString(current.map { if (it.fileId == fileId) block(it) else it })
        }
    }

    suspend fun remove(fileId: String) {
        context.downloadDataStore.edit { prefs ->
            val current = prefs[DOWNLOAD_KEY]
                ?.let { runCatching { Json.decodeFromString<List<DownloadEntry>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[DOWNLOAD_KEY] = Json.encodeToString(current.filterNot { it.fileId == fileId })
        }
    }
}
