package com.driveplayer.ui.downloads

import android.app.DownloadManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.model.DriveFile
import com.driveplayer.di.AppModule
import com.driveplayer.player.DownloadEntry
import com.driveplayer.player.DownloadStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

private const val MAX_CONCURRENT = 1

class DownloadsViewModel : ViewModel() {

    private val store = AppModule.downloadStore
    private val dm    = AppModule.driveDownloadManager

    private val _downloads = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val downloads: StateFlow<List<DownloadProgress>> = _downloads

    init {
        viewModelScope.launch {
            reconcileOnStartup()
            syncFromStore()   // collects forever — must run after reconcile
        }
        viewModelScope.launch { pollAndAdvanceQueue() }
    }

    // ── Startup reconciliation ────────────────────────────────────────────────
    // Fix up any in-flight entries left over from a previous app session.

    private suspend fun reconcileOnStartup() {
        store.downloads.first()
            .filter { it.status == DownloadStatus.RUNNING }
            .forEach { entry ->
                if (entry.downloadManagerId < 0) {
                    // Never actually submitted to DM — treat as still queued
                    store.update(entry.fileId) { it.copy(status = DownloadStatus.QUEUED) }
                    return@forEach
                }
                when (dm.queryStatus(entry.downloadManagerId)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getLocalUri(entry.downloadManagerId)?.toString()
                        store.update(entry.fileId) { it.copy(status = DownloadStatus.COMPLETED, localPath = uri) }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val (dl, total) = dm.queryProgress(entry.downloadManagerId)
                        store.update(entry.fileId) {
                            it.copy(status = DownloadStatus.FAILED, bytesDownloaded = dl, totalBytes = total.coerceAtLeast(0L))
                        }
                    }
                    else -> {
                        // DM no longer knows about it; re-queue so it gets re-submitted
                        store.update(entry.fileId) { it.copy(status = DownloadStatus.QUEUED, downloadManagerId = -1L) }
                    }
                }
            }
    }

    // ── Store sync ────────────────────────────────────────────────────────────
    // Mirrors DataStore → _downloads, preserving live progress for RUNNING entries
    // (bytes are written to _downloads directly in the poll loop, not to the store,
    //  to avoid excessive DataStore writes every 500 ms).

    private suspend fun syncFromStore() {
        store.downloads.collect { entries ->
            val current = _downloads.value.associateBy { it.entry.fileId }
            _downloads.value = entries.map { entry ->
                val existing = current[entry.fileId]
                val keepInMemoryBytes = entry.status == DownloadStatus.RUNNING
                DownloadProgress(
                    entry = entry,
                    bytesDownloaded = if (keepInMemoryBytes) existing?.bytesDownloaded ?: 0L else entry.bytesDownloaded,
                    totalBytes      = if (keepInMemoryBytes) existing?.totalBytes      ?: 0L else entry.totalBytes,
                )
            }
        }
    }

    // ── Queue + progress loop ─────────────────────────────────────────────────
    // Single loop: start the next queued download when a slot is free,
    // then refresh byte-progress for whatever is currently running.

    private suspend fun pollAndAdvanceQueue() {
        while (currentCoroutineContext().isActive) {
            delay(500)

            val snapshot = _downloads.value

            // ── Advance queue ─────────────────────────────────────────────
            val runningCount = snapshot.count { it.entry.status == DownloadStatus.RUNNING }
            if (runningCount < MAX_CONCURRENT) {
                val next = snapshot
                    .filter { it.entry.status == DownloadStatus.QUEUED && it.entry.accessToken != null }
                    .minByOrNull { it.entry.enqueuedAt }

                if (next != null) {
                    val entry = next.entry
                    val dmId = dm.enqueue(
                        DriveFile(id = entry.fileId, name = entry.title, mimeType = entry.mimeType),
                        entry.accessToken!!
                    )
                    store.update(entry.fileId) { it.copy(downloadManagerId = dmId, status = DownloadStatus.RUNNING) }
                }
            }

            // ── Poll running downloads ────────────────────────────────────
            snapshot.filter { it.entry.status == DownloadStatus.RUNNING && it.entry.downloadManagerId >= 0 }
                .forEach { dp ->
                    when (dm.queryStatus(dp.entry.downloadManagerId)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val uri = dm.getLocalUri(dp.entry.downloadManagerId)?.toString()
                            store.update(dp.entry.fileId) {
                                it.copy(status = DownloadStatus.COMPLETED, localPath = uri)
                            }
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val (dl, total) = dm.queryProgress(dp.entry.downloadManagerId)
                            store.update(dp.entry.fileId) {
                                it.copy(status = DownloadStatus.FAILED, bytesDownloaded = dl, totalBytes = total.coerceAtLeast(0L))
                            }
                        }
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                            val (downloaded, total) = dm.queryProgress(dp.entry.downloadManagerId)
                            // Write bytes directly to _downloads — no DataStore write needed here.
                            _downloads.update { list ->
                                list.map {
                                    if (it.entry.fileId == dp.entry.fileId)
                                        it.copy(bytesDownloaded = downloaded, totalBytes = total.coerceAtLeast(0L))
                                    else it
                                }
                            }
                        }
                        else -> {
                            // DM dropped the download unexpectedly — re-queue
                            store.update(dp.entry.fileId) { it.copy(status = DownloadStatus.QUEUED, downloadManagerId = -1L) }
                        }
                    }
                }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun cancel(entry: DownloadEntry) {
        if (entry.downloadManagerId >= 0) {
            val (downloaded, total) = dm.queryProgress(entry.downloadManagerId)
            dm.cancel(entry.downloadManagerId)
            viewModelScope.launch {
                store.update(entry.fileId) {
                    it.copy(status = DownloadStatus.CANCELLED, bytesDownloaded = downloaded, totalBytes = total.coerceAtLeast(0L))
                }
            }
        } else {
            viewModelScope.launch {
                store.update(entry.fileId) { it.copy(status = DownloadStatus.CANCELLED) }
            }
        }
    }

    fun delete(entry: DownloadEntry) {
        if (entry.downloadManagerId >= 0) dm.cancel(entry.downloadManagerId)
        entry.localPath?.let { path ->
            val file = File(Uri.parse(path).path ?: return@let)
            if (file.exists()) file.delete()
        }
        viewModelScope.launch { store.remove(entry.fileId) }
    }

    fun retry(entry: DownloadEntry) {
        if (entry.accessToken == null) return
        viewModelScope.launch {
            // Re-save as QUEUED with sentinel dmId — pollAndAdvanceQueue will enqueue it when a slot opens
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
        }
    }

    fun getPlayUri(entry: DownloadEntry): Uri? =
        entry.localPath?.let { Uri.parse(it) }
            ?: if (entry.downloadManagerId >= 0) dm.getLocalUri(entry.downloadManagerId) else null
}
