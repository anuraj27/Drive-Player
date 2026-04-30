package com.driveplayer.ui.downloads

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driveplayer.di.AppModule
import com.driveplayer.player.DownloadEntry
import com.driveplayer.player.DownloadService
import com.driveplayer.player.DownloadStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DownloadProgress(
    val entry: DownloadEntry,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val formattedProgress: String
        get() = if (totalBytes > 0) "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}" else ""
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

/**
 * Pure UI binder. The download QUEUE and DownloadManager polling now live in
 * [DownloadService] so they keep advancing while the app is closed; this VM
 * just renders state and forwards user actions.
 *
 * Live byte progress is read from [AppModule.liveDownloadProgress], which the
 * service writes to every ~500 ms. Terminal entries (COMPLETED/FAILED/CANCELLED)
 * carry their final byte counts in [DownloadEntry] itself.
 */
class DownloadsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppModule.downloadStore
    private val dm    = AppModule.driveDownloadManager
    private val live  = AppModule.liveDownloadProgress

    val downloads: StateFlow<List<DownloadProgress>> =
        combine(store.downloads, live) { entries, liveMap ->
            entries.map { entry ->
                val running = entry.status == DownloadStatus.RUNNING
                val (dl, total) = if (running) liveMap[entry.fileId] ?: (entry.bytesDownloaded to entry.totalBytes)
                                  else entry.bytesDownloaded to entry.totalBytes
                DownloadProgress(
                    entry = entry,
                    bytesDownloaded = dl,
                    totalBytes = total,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Actions ───────────────────────────────────────────────────────────────

    fun cancel(entry: DownloadEntry) {
        viewModelScope.launch {
            if (entry.downloadManagerId >= 0) {
                val (downloaded, total) = dm.queryProgress(entry.downloadManagerId)
                runCatching { dm.cancel(entry.downloadManagerId) }
                store.update(entry.fileId) {
                    it.copy(
                        status = DownloadStatus.CANCELLED,
                        bytesDownloaded = downloaded,
                        totalBytes = total.coerceAtLeast(0L),
                    )
                }
            } else {
                store.update(entry.fileId) { it.copy(status = DownloadStatus.CANCELLED) }
            }
            live.value = live.value - entry.fileId
        }
    }

    fun delete(entry: DownloadEntry) {
        if (entry.downloadManagerId >= 0) runCatching { dm.cancel(entry.downloadManagerId) }
        entry.localPath?.let { path ->
            val file = File(Uri.parse(path).path ?: return@let)
            if (file.exists()) file.delete()
        }
        viewModelScope.launch {
            store.remove(entry.fileId)
            live.value = live.value - entry.fileId
        }
    }

    fun retry(entry: DownloadEntry) {
        if (entry.accessToken == null) return
        viewModelScope.launch {
            store.save(
                entry.copy(
                    downloadManagerId = -1L,
                    status = DownloadStatus.QUEUED,
                    localPath = null,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    enqueuedAt = System.currentTimeMillis(),
                )
            )
            // Kick the service so it picks up the re-queued entry. Safe to call
            // even if the service is already running — the launcher is idempotent.
            DownloadService.start(getApplication())
        }
    }

    fun getPlayUri(entry: DownloadEntry): Uri? =
        entry.localPath?.let { Uri.parse(it) }
            ?: if (entry.downloadManagerId >= 0) dm.getLocalUri(entry.downloadManagerId) else null
}
