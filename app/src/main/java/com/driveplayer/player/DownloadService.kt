package com.driveplayer.player

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.driveplayer.data.model.DriveFile
import com.driveplayer.di.AppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the download queue advancing while the app is in
 * the background or fully closed.
 *
 * Why a service:
 *  - Android's [DownloadManager] runs in its own system process, so individual
 *    downloads survive our process death. But our queue advancement (max 1
 *    concurrent — only enqueue #2 when #1 finishes) and progress mirroring lives
 *    in app code. Without a foreground service that loop dies with the activity
 *    and queued #2 never starts.
 *  - Android 12+ requires this kind of "user-initiated long-running data sync"
 *    work to either be a foreground service or a WorkManager worker; an FGS gives
 *    us a live progress notification for free.
 *
 * Lifecycle:
 *  - Started from [MainActivity] on launch (if pending entries exist), from
 *    [com.driveplayer.ui.browser.FileBrowserViewModel] when a new download is
 *    enqueued, and from [com.driveplayer.ui.downloads.DownloadsViewModel] on retry.
 *  - Self-stops via [stopSelf] when there are no QUEUED + no RUNNING entries left.
 *  - [Service.START_STICKY] so the system restarts us if killed mid-download;
 *    onCreate re-runs reconcile to pick up wherever DownloadManager got to.
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_CANCEL_ALL = "com.driveplayer.action.CANCEL_ALL_DOWNLOADS"

        /** Single concurrent active download — keep aligned with the UI expectation. */
        private const val MAX_CONCURRENT = 1
        private const val POLL_INTERVAL_MS = 500L

        /** Starts the service if it isn't already running. Safe to call from any UI thread. */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var loopJob: Job? = null
    private lateinit var nm: NotificationManager
    private val store get() = AppModule.downloadStore
    private val dm get() = AppModule.driveDownloadManager
    private val live get() = AppModule.liveDownloadProgress

    @Volatile private var hasReconciled = false
    @Volatile private var lastNotifKey: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        DownloadNotifications.ensureChannels(this, nm)

        // We MUST call startForeground within ~5s of onCreate. Use a placeholder
        // notification immediately and let the loop replace it as soon as it has
        // real progress data.
        startForegroundCompat(buildPlaceholderNotification())

        // Sweep stale completed downloads against the user's auto-delete window.
        // Runs on every service start (not just app launch) so an entry that ages
        // past its threshold while the service is alive still gets removed on the
        // next user-initiated download.
        scope.launch { runAutoCleanup() }
    }

    /**
     * Delete every COMPLETED entry whose `completedAt` is older than the user's
     * "Auto-delete after" preference. The on-disk file is removed first, then
     * the entry from DataStore. Failures are swallowed (best-effort) — a stuck
     * entry must never block the user starting fresh downloads.
     */
    private suspend fun runAutoCleanup() {
        val days = AppModule.settingsStore.snapshot().autoDeleteDownloadsDays
        if (days <= 0) return // 0 = "Never"
        val now = System.currentTimeMillis()
        val cutoff = now - days * 24L * 60L * 60L * 1000L
        val expired = store.downloads.first().filter { entry ->
            entry.status == DownloadStatus.COMPLETED &&
                (entry.completedAt ?: 0L) > 0L &&
                (entry.completedAt ?: 0L) < cutoff
        }
        for (entry in expired) {
            runCatching {
                entry.localPath?.let { path ->
                    val uri = android.net.Uri.parse(path)
                    when (uri.scheme) {
                        "file"    -> uri.path?.let { java.io.File(it).delete() }
                        "content" -> contentResolver.delete(uri, null, null)
                    }
                }
            }
            runCatching { store.remove(entry.fileId) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            scope.launch { cancelAll() }
            return START_NOT_STICKY
        }
        // Idempotent: only one loop runs even across multiple start commands.
        // Also covers the corner where a previous loop completed (queue went empty
        // + stopSelf) but a fresh start command arrived before onDestroy fired.
        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }
        // STICKY so Android resumes the service after a kill; onCreate will
        // re-establish the foreground notification and re-run reconcile.
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel(); loopJob = null
        scope.cancel()
        live.value = emptyMap()
        super.onDestroy()
    }

    // ── Queue + progress loop ─────────────────────────────────────────────────

    private suspend fun runLoop() {
        if (!hasReconciled) {
            reconcile()
            hasReconciled = true
        }
        while (currentCoroutineContext().isActive) {
            val entries = store.downloads.first()
            val running = entries.filter { it.status == DownloadStatus.RUNNING }
            val queued  = entries.filter { it.status == DownloadStatus.QUEUED }

            // Nothing to do — empty queue means we can stop.
            if (running.isEmpty() && queued.isEmpty()) {
                stopSelf()
                return
            }

            // Promote queued → running if a slot is free.
            if (running.size < MAX_CONCURRENT) {
                queued.filter { it.accessToken != null }
                    .minByOrNull { it.enqueuedAt }
                    ?.let { entry ->
                        val dmId = dm.enqueue(
                            DriveFile(id = entry.fileId, name = entry.title, mimeType = entry.mimeType),
                            entry.accessToken!!
                        )
                        store.update(entry.fileId) {
                            it.copy(downloadManagerId = dmId, status = DownloadStatus.RUNNING)
                        }
                    }
            }

            // Poll every running download.
            running.filter { it.downloadManagerId >= 0 }.forEach { entry ->
                pollOne(entry)
            }

            updateForegroundNotification()

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollOne(entry: DownloadEntry) {
        when (dm.queryStatus(entry.downloadManagerId)) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                val uri = dm.getLocalUri(entry.downloadManagerId)?.toString()
                store.update(entry.fileId) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        localPath = uri,
                        completedAt = System.currentTimeMillis(),
                    )
                }
                live.update { it - entry.fileId }
                DownloadNotifications.postCompleted(this, nm, entry.fileId, entry.title)
            }
            DownloadManager.STATUS_FAILED -> {
                val (dl, total) = dm.queryProgress(entry.downloadManagerId)
                store.update(entry.fileId) {
                    it.copy(status = DownloadStatus.FAILED, bytesDownloaded = dl, totalBytes = total.coerceAtLeast(0L))
                }
                live.update { it - entry.fileId }
                DownloadNotifications.postFailed(this, nm, entry.fileId, entry.title)
            }
            DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                val (downloaded, total) = dm.queryProgress(entry.downloadManagerId)
                live.update { it + (entry.fileId to (downloaded to total.coerceAtLeast(0L))) }
            }
            else -> {
                // DM dropped the download unexpectedly — re-queue.
                store.update(entry.fileId) {
                    it.copy(status = DownloadStatus.QUEUED, downloadManagerId = -1L)
                }
                live.update { it - entry.fileId }
            }
        }
    }

    /**
     * Look at every entry in RUNNING state and reconcile against DownloadManager:
     * picks up SUCCESS / FAIL that completed while our process was dead, and
     * re-queues entries whose dmId is gone (e.g. after a reboot).
     */
    private suspend fun reconcile() {
        store.downloads.first()
            .filter { it.status == DownloadStatus.RUNNING }
            .forEach { entry ->
                if (entry.downloadManagerId < 0) {
                    store.update(entry.fileId) { it.copy(status = DownloadStatus.QUEUED) }
                    return@forEach
                }
                when (dm.queryStatus(entry.downloadManagerId)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getLocalUri(entry.downloadManagerId)?.toString()
                        store.update(entry.fileId) {
                            it.copy(
                                status = DownloadStatus.COMPLETED,
                                localPath = uri,
                                completedAt = it.completedAt ?: System.currentTimeMillis(),
                            )
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val (dl, total) = dm.queryProgress(entry.downloadManagerId)
                        store.update(entry.fileId) {
                            it.copy(status = DownloadStatus.FAILED, bytesDownloaded = dl, totalBytes = total.coerceAtLeast(0L))
                        }
                    }
                    else -> {
                        store.update(entry.fileId) {
                            it.copy(status = DownloadStatus.QUEUED, downloadManagerId = -1L)
                        }
                    }
                }
            }
    }

    private suspend fun cancelAll() {
        val entries = store.downloads.first()
        entries.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
            .forEach { entry ->
                if (entry.downloadManagerId >= 0) {
                    val (dl, total) = dm.queryProgress(entry.downloadManagerId)
                    runCatching { dm.cancel(entry.downloadManagerId) }
                    store.update(entry.fileId) {
                        it.copy(status = DownloadStatus.CANCELLED, bytesDownloaded = dl, totalBytes = total.coerceAtLeast(0L))
                    }
                } else {
                    store.update(entry.fileId) { it.copy(status = DownloadStatus.CANCELLED) }
                }
            }
        live.value = emptyMap()
        stopSelf()
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun buildPlaceholderNotification() = DownloadNotifications.buildOngoing(
        context = this,
        title = "Preparing download…",
        contentText = "Starting up",
        progressPercent = 0,
        indeterminate = true,
        queuedWaiting = 0,
    )

    private suspend fun updateForegroundNotification() {
        val entries = store.downloads.first()
        val running = entries.firstOrNull { it.status == DownloadStatus.RUNNING }
        // Number of QUEUED entries that are NOT yet running. The running one
        // is represented by the notification title — don't double-count it.
        val queuedWaiting = entries.count { it.status == DownloadStatus.QUEUED }

        if (running == null) {
            // Nothing actively running yet, but we have a queued entry waiting
            // for its slot to open — show a "preparing" state. The waiting list
            // count is queuedWaiting MINUS one, because one of those is about
            // to be promoted to running and is what the title represents.
            val displayWaiting = (queuedWaiting - 1).coerceAtLeast(0)
            val key = "queued:$queuedWaiting"
            if (key != lastNotifKey) {
                lastNotifKey = key
                nm.notify(
                    DownloadNotifications.FOREGROUND_NOTIF_ID,
                    DownloadNotifications.buildOngoing(
                        context = this,
                        title = if (queuedWaiting > 0) "Preparing download…" else "Finishing up",
                        contentText = "",
                        progressPercent = 0,
                        indeterminate = true,
                        queuedWaiting = displayWaiting,
                    )
                )
            }
            return
        }

        val (downloaded, total) = live.value[running.fileId] ?: (0L to 0L)
        val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val indeterminate = total <= 0L
        val key = "${running.fileId}:$percent:$indeterminate:$queuedWaiting"
        if (key == lastNotifKey) return
        lastNotifKey = key

        val sub = if (total > 0L) "$percent%  ·  ${formatBytes(downloaded)} / ${formatBytes(total)}"
                  else if (downloaded > 0L) formatBytes(downloaded) + " downloaded"
                  else "Connecting…"

        nm.notify(
            DownloadNotifications.FOREGROUND_NOTIF_ID,
            DownloadNotifications.buildOngoing(
                context = this,
                title = running.title,
                contentText = sub,
                progressPercent = percent,
                indeterminate = indeterminate,
                queuedWaiting = queuedWaiting,
            )
        )
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifications.FOREGROUND_NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(DownloadNotifications.FOREGROUND_NOTIF_ID, notification)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
        else                    -> "$bytes B"
    }
}
