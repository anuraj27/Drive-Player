package com.driveplayer.data.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Scans device videos via MediaStore and groups them by folder.
 */
class LocalVideoRepository(private val context: Context) {

    /**
     * Wraps a suspending block in a [Result] but RETHROWS [CancellationException]
     * so structured concurrency stays intact. See the equivalent helper in
     * [com.driveplayer.data.remote.DriveRepository] for the full rationale —
     * tl;dr `kotlin.runCatching` would swallow cancellation and let the
     * VM's `onFailure` lambda paint a "Canceled" error onto the UI when the
     * user is just typing fast.
     */
    private inline fun <T> safeCall(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Returns all video files on the device, grouped by parent folder.
     */
    suspend fun getVideoFolders(): Result<List<VideoFolder>> = withContext(Dispatchers.IO) {
        safeCall {
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
            safeCall {
                scanVideos().filter { it.folderPath == folderPath }
                    .sortedBy { it.title.lowercase() }
            }
        }

    /**
     * Tokenised AND substring search across the user's local video library.
     *
     * Each whitespace-separated word in [query] must appear (case-insensitive)
     * either in the file's title OR its folder name OR its full path. This lets
     * "school 2024" find "Math Lecture.mp4" inside "/storage/.../School 2024/"
     * even though no single field contains the full query string.
     *
     * Results are sorted by recency (DATE_MODIFIED desc) — same MediaStore order
     * as the underlying scan, since for a search the user usually wants the
     * latest matching file.
     */
    suspend fun searchVideos(query: String): Result<List<LocalVideo>> =
        withContext(Dispatchers.IO) {
            safeCall {
                val tokens = query.trim().split(Regex("\\s+"))
                    .filter { it.length >= 2 }
                    .map { it.lowercase() }
                if (tokens.isEmpty()) return@safeCall emptyList()

                scanVideos().filter { v ->
                    // Match against a single concatenated haystack so a token
                    // can hit the title OR folder OR path interchangeably.
                    val haystack = "${v.title} ${v.folderName} ${v.path}".lowercase()
                    tokens.all { tok -> haystack.contains(tok) }
                }
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
