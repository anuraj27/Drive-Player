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
    /**
     * Optional explicit key used by the resumable-position store. When null we fall back
     * to "local_<MediaStore id>". Non-null is used for synthetic videos (e.g. played
     * downloads) where [id] is `-1L` but we still want resume support — the caller passes
     * something stable like "download_<driveFileId>".
     */
    val positionKey: String? = null,
    /** Pixel width  — `0` when MediaStore didn't populate the column (older index, weird codec). */
    val width: Int = 0,
    /** Pixel height — same caveat as [width]. */
    val height: Int = 0,
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

    /** Marketing-style label for the smaller dimension — null when we don't know. */
    val qualityLabel: String? get() = qualityLabelFor(width, height)
}

/**
 * Friendly resolution label derived from raw pixel dimensions. Uses the
 * smaller of width/height (i.e. the "rotated" height for landscape video) so
 * a 1920x1080 portrait clip and a 1080x1920 vertical reel both report
 * "1080p", matching how YouTube / VLC label them.
 *
 * Returns null for `<480` so we don't waste pixels labelling a tiny clip
 * "144p" — caller hides the chip in that case.
 */
fun qualityLabelFor(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    val minDim = minOf(width, height)
    return when {
        minDim >= 2160 -> "4K"
        minDim >= 1440 -> "2K"
        minDim >= 1080 -> "1080p"
        minDim >= 720  -> "720p"
        minDim >= 480  -> "SD"
        else           -> null
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
