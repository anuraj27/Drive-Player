package com.driveplayer.ui.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.driveplayer.di.AppModule
import com.driveplayer.player.DownloadEntry

class DownloadNotificationReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val fileId = intent.getStringExtra(DownloadNotificationManager.EXTRA_FILE_ID) ?: return
        
        when (intent.action) {
            DownloadNotificationManager.ACTION_CANCEL -> {
                handleCancelAction(fileId)
            }
            DownloadNotificationManager.ACTION_RETRY -> {
                handleRetryAction(fileId)
            }
        }
    }
    
    private fun handleCancelAction(fileId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val store = AppModule.downloadStore
                val downloads = store.downloads.first()
                val entry = downloads.find { it.fileId == fileId }
                if (entry != null) {
                    val dm = AppModule.driveDownloadManager
                    dm.cancel(entry.downloadManagerId)
                    store.update(fileId) { it.copy(status = com.driveplayer.player.DownloadStatus.CANCELLED) }
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }
    }
    
    private fun handleRetryAction(fileId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val store = AppModule.downloadStore
                val downloads = store.downloads.first()
                val entry = downloads.find { it.fileId == fileId }
                if (entry != null && entry.accessToken != null) {
                    val dm = AppModule.driveDownloadManager
                    val newDmId = dm.enqueue(
                        com.driveplayer.data.model.DriveFile(
                            id = entry.fileId,
                            name = entry.title,
                            mimeType = entry.mimeType
                        ),
                        entry.accessToken
                    )
                    store.save(
                        entry.copy(
                            downloadManagerId = newDmId,
                            status = com.driveplayer.player.DownloadStatus.QUEUED,
                            localPath = null,
                            bytesDownloaded = 0L,
                            totalBytes = 0L,
                            enqueuedAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                // Log error if needed
            }
        }
    }
}
