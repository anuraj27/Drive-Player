package com.driveplayer.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.driveplayer.MainActivity

/**
 * Notification channels and builders for the background download pipeline.
 *
 * Two channels:
 *  - [CHANNEL_PROGRESS]   ongoing foreground notification, LOW importance (silent).
 *  - [CHANNEL_COMPLETED]  per-file completion alerts, DEFAULT importance (sound).
 *
 * Channel creation is idempotent — [NotificationManager.createNotificationChannel]
 * is a no-op when the channel already exists, and the user's per-channel settings
 * are preserved across creates.
 */
object DownloadNotifications {

    const val CHANNEL_PROGRESS  = "drive_downloads_progress"
    const val CHANNEL_COMPLETED = "drive_downloads_completed"

    /** Notification id for the ongoing foreground service notification. */
    const val FOREGROUND_NOTIF_ID = 0xD0001

    /** Base id for completion notifications; we add `fileId.hashCode()` to keep them per-file. */
    private const val COMPLETED_NOTIF_BASE = 0xD0100

    fun ensureChannels(context: Context, nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "Downloads in progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress of files being downloaded from Drive."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_COMPLETED,
                "Downloads completed",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when a download has finished."
                setShowBadge(true)
            }
        )
    }

    /**
     * Builds the ongoing notification shown while [DownloadService] is in the
     * foreground state. Tapping it opens [MainActivity]; a Cancel action stops
     * the service (which cancels the active download via DownloadManager).
     *
     * @param queuedWaiting number of entries that are QUEUED but not yet running.
     *                      The currently-running entry is already represented by
     *                      [title]/[contentText], so do NOT include it here.
     */
    fun buildOngoing(
        context: Context,
        title: String,
        contentText: String,
        progressPercent: Int,
        indeterminate: Boolean,
        queuedWaiting: Int,
    ): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelAll = PendingIntent.getService(
            context,
            1,
            Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val subText = when {
            queuedWaiting <= 0 -> null
            queuedWaiting == 1 -> "1 in queue"
            else               -> "$queuedWaiting in queue"
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(subText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (indeterminate) 0 else 100, progressPercent.coerceIn(0, 100), indeterminate)
            .addAction(0, "Cancel all", cancelAll)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * One-shot completion notification posted after a file finishes downloading.
     * Tapping it opens the app on the Downloads tab via [MainActivity].
     */
    fun postCompleted(context: Context, nm: NotificationManager, fileId: String, title: String) {
        val openApp = PendingIntent.getActivity(
            context,
            fileId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(COMPLETED_NOTIF_BASE + fileId.hashCode(), notif)
    }

    /** One-shot failure notification for a download that finished in FAILED state. */
    fun postFailed(context: Context, nm: NotificationManager, fileId: String, title: String) {
        val openApp = PendingIntent.getActivity(
            context,
            fileId.hashCode() xor 0x1,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title — tap to retry from the Downloads tab."))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(COMPLETED_NOTIF_BASE + (fileId.hashCode() xor 0x1), notif)
    }
}
