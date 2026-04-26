package com.driveplayer.ui.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.driveplayer.R
import com.driveplayer.player.DownloadEntry
import com.driveplayer.player.DownloadStatus

class DownloadNotificationManager(private val context: Context) {
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val CHANNEL_NAME = "Download Progress"
        private const val NOTIFICATION_ID_BASE = 1000
        const val ACTION_CANCEL = "com.driveplayer.DOWNLOAD_CANCEL"
        const val ACTION_RETRY = "com.driveplayer.DOWNLOAD_RETRY"
        const val EXTRA_FILE_ID = "file_id"
    }
    
    init {
        createNotificationChannel()
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }
    
    fun updateDownloadNotification(entry: DownloadEntry, progress: DownloadProgress) {
        val notificationId = NOTIFICATION_ID_BASE + entry.fileId.hashCode()
        
        when (entry.status) {
            DownloadStatus.RUNNING -> {
                val notification = createProgressNotification(entry, progress)
                notificationManager.notify(notificationId, notification)
            }
            DownloadStatus.COMPLETED -> {
                val notification = createCompletedNotification(entry)
                notificationManager.notify(notificationId, notification)
                // Auto-dismiss after 5 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    notificationManager.cancel(notificationId)
                }, 5000)
            }
            DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                val notification = createFailedNotification(entry, progress)
                notificationManager.notify(notificationId, notification)
            }
            DownloadStatus.QUEUED -> {
                val notification = createQueuedNotification(entry)
                notificationManager.notify(notificationId, notification)
            }
        }
    }
    
    fun cancelNotification(entry: DownloadEntry) {
        val notificationId = NOTIFICATION_ID_BASE + entry.fileId.hashCode()
        notificationManager.cancel(notificationId)
    }
    
    private fun createProgressNotification(entry: DownloadEntry, progress: DownloadProgress): Notification {
        val progressPercent = (progress.fraction * 100).toInt()
        
        // Create cancel intent
        val cancelIntent = Intent(ACTION_CANCEL).apply {
            putExtra(EXTRA_FILE_ID, entry.fileId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 
            entry.fileId.hashCode(), 
            cancelIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Always show percentage and MB data
        val contentText = if (progress.totalBytes > 0) {
            "${progressPercent}% • ${progress.formattedProgress}"
        } else {
            "${progressPercent}% • ${progress.bytesDownloaded} B"
        }
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Downloading: ${entry.title}")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, false)
            .addAction(R.drawable.ic_error, "Cancel", cancelPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
    
    private fun createCompletedNotification(entry: DownloadEntry): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download completed")
            .setContentText(entry.title)
            .setSmallIcon(R.drawable.ic_check)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }
    
    private fun createFailedNotification(entry: DownloadEntry, progress: DownloadProgress): Notification {
        // Create retry intent
        val retryIntent = Intent(ACTION_RETRY).apply {
            putExtra(EXTRA_FILE_ID, entry.fileId)
        }
        val retryPendingIntent = PendingIntent.getBroadcast(
            context, 
            entry.fileId.hashCode() + 1000, 
            retryIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Download failed")
            .setContentText("${entry.title}${if (progress.totalBytes > 0) " • ${progress.formattedProgress}" else ""}")
            .setSmallIcon(R.drawable.ic_error)
            .addAction(R.drawable.ic_download, "Retry", retryPendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
    }
    
    private fun createQueuedNotification(entry: DownloadEntry): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Queued for download")
            .setContentText(entry.title)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
}
