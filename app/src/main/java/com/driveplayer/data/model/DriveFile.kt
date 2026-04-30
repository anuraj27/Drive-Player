package com.driveplayer.data.model

import com.driveplayer.data.local.qualityLabelFor
import com.google.gson.annotations.SerializedName

data class DriveFile(
    @SerializedName("id")           val id: String,
    @SerializedName("name")         val name: String,
    @SerializedName("mimeType")     val mimeType: String,
    @SerializedName("size")         val size: String? = null,
    @SerializedName("modifiedTime") val modifiedTime: String? = null,
    @SerializedName("thumbnailLink")val thumbnailLink: String? = null,
    @SerializedName("owners")       val owners: List<Owner>? = null,
    @SerializedName("parents")      val parents: List<String>? = null,
    /**
     * Drive's per-video metadata block — populated only when the request asks
     * for `videoMediaMetadata(durationMillis,width,height)` in `fields`.
     * Always null for folders / non-video files.
     */
    @SerializedName("videoMediaMetadata") val videoMediaMetadata: VideoMediaMetadata? = null,
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
    val isVideo: Boolean  get() = mimeType.startsWith("video/")
    val isSrt: Boolean    get() = name.endsWith(".srt", ignoreCase = true)

    val formattedSize: String
        get() {
            val bytes = size?.toLongOrNull() ?: return ""
            return when {
                bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
                bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
                bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024.0)
                else                    -> "$bytes B"
            }
        }

    /** Video duration in milliseconds, or `null` when Drive didn't return it. */
    val durationMs: Long? get() = videoMediaMetadata?.durationMillis?.toLongOrNull()

    /** "12:34" / "1:02:03" format. Null when duration is unknown. */
    val formattedDuration: String?
        get() {
            val ms = durationMs ?: return null
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

    /** "1080p" / "4K" / null. Shared rule with [com.driveplayer.data.local.LocalVideo]. */
    val qualityLabel: String?
        get() = videoMediaMetadata?.let { qualityLabelFor(it.width ?: 0, it.height ?: 0) }
}

/**
 * Drive returns `durationMillis` as a string in JSON (it's `int64` on the
 * wire), so we decode it as String and parse on read. Width/height are
 * regular Int fields. All three are optional — Drive omits the block for
 * non-video files and may omit individual sub-fields for malformed media.
 */
data class VideoMediaMetadata(
    @SerializedName("durationMillis") val durationMillis: String? = null,
    @SerializedName("width")          val width: Int? = null,
    @SerializedName("height")         val height: Int? = null,
)

data class DriveFileListResponse(
    @SerializedName("files")         val files: List<DriveFile> = emptyList(),
    @SerializedName("nextPageToken") val nextPageToken: String?  = null,
)

data class Owner(
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("emailAddress") val emailAddress: String? = null,
)
