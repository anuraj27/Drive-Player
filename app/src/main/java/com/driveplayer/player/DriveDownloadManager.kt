package com.driveplayer.player

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.driveplayer.data.model.DriveFile
import com.driveplayer.di.AppModule
import kotlinx.coroutines.runBlocking
import java.io.File

class DriveDownloadManager(private val context: Context) {

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun enqueue(file: DriveFile, accessToken: String): Long {
        val url = "https://www.googleapis.com/drive/v3/files/${file.id}?alt=media"
        val destDir = context.getExternalFilesDir(null) ?: context.filesDir
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val destFile = File(destDir, "${file.id}_$safeName")

        // Read the user's "Wi-Fi only" preference at enqueue time. Reading it
        // here (vs. caching at construction) means flipping the switch in
        // Settings affects subsequent queue additions immediately, without
        // needing the app to restart or the manager to be rebuilt.
        val wifiOnly = runCatching {
            runBlocking { AppModule.settingsStore.snapshot().downloadsWifiOnly }
        }.getOrDefault(false)

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(file.name)
            .setDescription("Drive Player")
            .addRequestHeader("Authorization", "Bearer $accessToken")
            .setDestinationUri(Uri.fromFile(destFile))
            // Hide the system DownloadManager notification — DownloadService
            // posts our own ongoing + completion notifications, so showing both
            // is redundant noise. Requires DOWNLOAD_WITHOUT_NOTIFICATION permission.
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(!wifiOnly)

        return dm.enqueue(request)
    }

    fun queryProgress(dmId: Long): Pair<Long, Long> {
        val cursor = dm.query(DownloadManager.Query().setFilterById(dmId))
        if (!cursor.moveToFirst()) { cursor.close(); return 0L to 0L }
        val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        cursor.close()
        return downloaded to total
    }

    fun queryStatus(dmId: Long): Int {
        val cursor = dm.query(DownloadManager.Query().setFilterById(dmId))
        if (!cursor.moveToFirst()) { cursor.close(); return DownloadManager.STATUS_FAILED }
        val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        cursor.close()
        return status
    }

    fun getLocalUri(dmId: Long): Uri? {
        val cursor = dm.query(DownloadManager.Query().setFilterById(dmId))
        if (!cursor.moveToFirst()) { cursor.close(); return null }
        val uriStr = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
        cursor.close()
        return uriStr?.let { Uri.parse(it) }
    }

    fun cancel(dmId: Long) {
        dm.remove(dmId)
    }
}
