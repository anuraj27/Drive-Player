package com.driveplayer.data.local

import android.net.Uri

/**
 * Represents a video found on the device via MediaStore.
 */
data class LocalVideo(
    val id: Long,
    val title: String,
    val path: String,
    val uri: Uri,
    val duration: Long,       // milliseconds
    val size: Long,           // bytes
    val folderName: String,
    val folderPath: String,
    val dateModified: Long,   // epoch seconds
) {
    val formattedDuration: String
        get() {
            val totalSec = duration / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

    val formattedSize: String
        get() = when {
            size >= 1_073_741_824L -> "%.1f GB".format(size / 1_073_741_824.0)
            size >= 1_048_576L     -> "%.1f MB".format(size / 1_048_576.0)
            size >= 1_024L         -> "%.1f KB".format(size / 1_024.0)
            else                   -> "$size B"
        }
}

/**
 * A folder grouping of local videos.
 */
data class VideoFolder(
    val name: String,
    val path: String,
    val videos: List<LocalVideo>,
    val thumbnailUri: Uri? = null, // first video's URI for thumbnail
) {
    val videoCount: Int get() = videos.size
    val totalSize: Long get() = videos.sumOf { it.size }
}
