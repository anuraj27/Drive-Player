package com.driveplayer.data.remote

import com.driveplayer.data.model.DriveFile

class DriveRepository(private val api: DriveApiService) {

    /**
     * Returns folders + video files inside [folderId].
     * Uses "root" when folderId is null (Drive root).
     * Handles pagination automatically — fetches all pages.
     */
    suspend fun listFolder(folderId: String?): Result<List<DriveFile>> = runCatching {
        val parentId = folderId ?: "root"
        // Server-side filter: only folders OR video/* OR .srt files
        val query = buildString {
            append("'$parentId' in parents")
            append(" and trashed = false")
            append(" and (")
            append("mimeType = 'application/vnd.google-apps.folder'")
            append(" or mimeType contains 'video/'")
            append(" or name contains '.srt'")
            append(")")
        }

        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null

        do {
            val response = api.listFiles(query = query, pageToken = pageToken)
            allFiles += response.files
            pageToken = response.nextPageToken
        } while (pageToken != null)

        // Sort: folders first, then alphabetical
        allFiles.sortedWith(compareByDescending<DriveFile> { it.isFolder }.thenBy { it.name })
    }

    /** Stream URL for a file — ExoPlayer fetches with Authorization header injected by OkHttp. */
    fun streamUrl(fileId: String): String =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

    /** SRT subtitle URL — same endpoint, Drive serves the raw bytes. */
    fun srtUrl(fileId: String): String = streamUrl(fileId)
}
