package com.driveplayer.data.model

import com.google.gson.annotations.SerializedName

data class DriveFile(
    @SerializedName("id")           val id: String,
    @SerializedName("name")         val name: String,
    @SerializedName("mimeType")     val mimeType: String,
    @SerializedName("size")         val size: String? = null,
    @SerializedName("modifiedTime") val modifiedTime: String? = null,
    @SerializedName("thumbnailLink")val thumbnailLink: String? = null,
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
}

data class DriveFileListResponse(
    @SerializedName("files")         val files: List<DriveFile> = emptyList(),
    @SerializedName("nextPageToken") val nextPageToken: String?  = null,
)
