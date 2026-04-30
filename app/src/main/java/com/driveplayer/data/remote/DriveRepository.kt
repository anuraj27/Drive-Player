package com.driveplayer.data.remote

import com.driveplayer.data.model.DriveFile
import kotlin.coroutines.cancellation.CancellationException

class DriveRepository(private val api: DriveApiService) {

    /**
     * Wrapper that turns a suspending block into a [Result], but — critically —
     * RETHROWS [CancellationException] instead of swallowing it into
     * `Result.failure`. The stdlib `kotlin.runCatching` catches every
     * [Throwable] including cancellation, which breaks structured concurrency:
     * when a search debounce job is cancelled mid-request, OkHttp throws
     * `IOException("Canceled")` (and the coroutine sees a CancellationException
     * for `delay()` cancellation). With `runCatching` those would bubble up as
     * `Result.failure`, the calling VM's `onFailure` lambda would still run on
     * the cancelled coroutine, and the user would see a brief red "Canceled"
     * error card flash on every keystroke that pre-empts an in-flight call.
     */
    private suspend inline fun <T> safeCall(crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Returns folders + video files inside [folderId].
     * Uses "root" when folderId is null (Drive root).
     * Handles pagination automatically — fetches all pages.
     */
    suspend fun listFolder(folderId: String?): Result<List<DriveFile>> = safeCall {
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

    suspend fun getSharedFiles(): Result<List<DriveFile>> = safeCall {
        val query = buildString {
            append("sharedWithMe = true")
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

    /**
     * Searches across all of Drive for video files matching [query].
     *
     * Behaviour:
     *  - Whitespace-tokenised AND search: each non-empty token becomes its own
     *    `name contains 'tok'` clause and they're joined with `and`. Drive's
     *    `name contains` is word-prefix matching (and case-insensitive), so
     *    typing "summer beach 2023" finds *Summer Vacation Beach 2023.mp4*
     *    even though no single contiguous substring matches the whole query.
     *  - Bounded pagination: at most [MAX_SEARCH_PAGES] pages (≈ 500 results
     *    with the default `pageSize=100`) so a search on a large drive
     *    completes in a predictable time. The first page is the most relevant
     *    via Drive's default ordering.
     *  - Returns videos only (`mimeType contains 'video/'`), not subtitles.
     */
    suspend fun searchVideos(query: String): Result<List<DriveFile>> = safeCall {
        // Tokenise on whitespace and escape each token defensively. Order
        // matters: escape backslashes BEFORE single quotes, otherwise the
        // backslash inserted by the quote-escape gets re-escaped and the query breaks.
        //
        // Drop 1-character tokens: Drive's `name contains 'a'` is a word-prefix
        // match and effectively returns "everything", which costs the server
        // and dilutes precision when AND-joined with real tokens.
        val tokens = query.trim().split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .map { it.replace("\\", "\\\\").replace("'", "\\'") }

        if (tokens.isEmpty()) return@safeCall emptyList()

        val q = buildString {
            tokens.forEachIndexed { i, tok ->
                if (i > 0) append(" and ")
                append("name contains '$tok'")
            }
            append(" and trashed = false")
            append(" and mimeType contains 'video/'")
        }

        val allFiles = mutableListOf<DriveFile>()
        var pageToken: String? = null
        var pages = 0

        do {
            val response = api.listFiles(query = q, pageToken = pageToken)
            allFiles += response.files
            pageToken = response.nextPageToken
            pages++
        } while (pageToken != null && pages < MAX_SEARCH_PAGES)

        allFiles.sortedBy { it.name }
    }

    private companion object {
        /** Cap on pages fetched per search to keep latency bounded on huge drives. */
        const val MAX_SEARCH_PAGES = 5
    }

    /** Stream URL for a file — ExoPlayer fetches with Authorization header injected by OkHttp. */
    fun streamUrl(fileId: String): String =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"

    /** SRT subtitle URL — same endpoint, Drive serves the raw bytes. */
    fun srtUrl(fileId: String): String = streamUrl(fileId)
}
