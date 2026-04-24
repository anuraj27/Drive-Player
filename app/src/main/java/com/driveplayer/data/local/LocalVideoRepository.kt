package com.driveplayer.data.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans device videos via MediaStore and groups them by folder.
 */
class LocalVideoRepository(private val context: Context) {

    /**
     * Returns all video files on the device, grouped by parent folder.
     */
    suspend fun getVideoFolders(): Result<List<VideoFolder>> = withContext(Dispatchers.IO) {
        runCatching {
            val videos = scanVideos()
            videos
                .groupBy { it.folderPath }
                .map { (folderPath, vids) ->
                    VideoFolder(
                        name = vids.first().folderName,
                        path = folderPath,
                        videos = vids.sortedBy { it.title.lowercase() },
                        thumbnailUri = vids.firstOrNull()?.uri
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    /**
     * Returns all videos inside a specific folder path.
     */
    suspend fun getVideosInFolder(folderPath: String): Result<List<LocalVideo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                scanVideos().filter { it.folderPath == folderPath }
                    .sortedBy { it.title.lowercase() }
            }
        }

    private fun scanVideos(): List<LocalVideo> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA,           // full path, needed for folder grouping
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )

        val selection = "${MediaStore.Video.Media.DURATION} > ?"
        val selectionArgs = arrayOf("0") // Skip 0-duration entries (thumbnails, corrupted)

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        val results = mutableListOf<LocalVideo>()

        context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol       = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val pathCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val bucketCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id   = cursor.getLong(idCol)
                val path = cursor.getString(pathCol) ?: continue
                val uri  = ContentUris.withAppendedId(collection, id)

                results += LocalVideo(
                    id          = id,
                    title       = cursor.getString(nameCol) ?: "Unknown",
                    path        = path,
                    uri         = uri,
                    duration    = cursor.getLong(durationCol),
                    size        = cursor.getLong(sizeCol),
                    folderName  = cursor.getString(bucketCol) ?: "Unknown",
                    folderPath  = path.substringBeforeLast('/'),
                    dateModified = cursor.getLong(dateCol),
                )
            }
        }
        return results
    }
}
