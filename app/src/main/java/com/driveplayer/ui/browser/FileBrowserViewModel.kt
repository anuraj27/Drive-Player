package com.driveplayer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.player.DownloadEntry
import com.driveplayer.player.DownloadService
import com.driveplayer.player.DownloadStatus
import com.driveplayer.player.PinnedFolder
import com.driveplayer.player.PlaybackPositionStore
import com.driveplayer.player.RecentSearchStore
import com.driveplayer.player.WatchEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TabMode { MY_DRIVE, SHARED }

sealed class BrowserState {
    object Loading : BrowserState()
    data class Success(val files: List<DriveFile>) : BrowserState()
    data class Error(val message: String) : BrowserState()
}

data class FolderEntry(val id: String?, val name: String)

class FileBrowserViewModel(
    private val repo: DriveRepository,
    private val accessToken: String,
) : ViewModel() {

    // ── Folder browsing ─────────────────────────────────────────────────────

    private val _state = MutableStateFlow<BrowserState>(BrowserState.Loading)
    val state: StateFlow<BrowserState> = _state

    private val _tabMode = MutableStateFlow(TabMode.MY_DRIVE)
    val tabMode: StateFlow<TabMode> = _tabMode

    private val _folderStack = MutableStateFlow<List<FolderEntry>>(
        listOf(FolderEntry(null, "My Drive"))
    )
    val folderStack: StateFlow<List<FolderEntry>> = _folderStack

    val currentFolder: FolderEntry get() = _folderStack.value.last()

    // ── Search ───────────────────────────────────────────────────────────────

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchState = MutableStateFlow<BrowserState?>(null)
    val searchState: StateFlow<BrowserState?> = _searchState

    private var searchDebounceJob: Job? = null

    /** The canonicalised (trimmed) query that is currently scheduled or has
     *  already been sent to Drive. Used to coalesce duplicate work caused by
     *  whitespace-only edits (trailing space, autocorrect re-emit) and by
     *  upstream state-flow re-collection — both produce a different RAW
     *  string but an identical query, and without this guard we cancel a
     *  good in-flight call only to re-fire the same one (visible in logcat
     *  as `HTTP FAILED: java.io.IOException: Canceled` followed by a 200
     *  with the exact same URL ~400 ms later). */
    private var inFlightQuery: String? = null

    /** Recent cloud search queries (newest first, max 8). Populated after a
     *  successful search; surfaced as quick-fill chips when the search bar is
     *  focused with an empty query. */
    val recentSearches: StateFlow<List<String>> =
        AppModule.recentSearchStore.recents(RecentSearchStore.Namespace.CLOUD)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Pinned folders ───────────────────────────────────────────────────────

    val pinnedFolders: StateFlow<List<PinnedFolder>> =
        AppModule.pinnedFolderStore.pinnedFolders
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Continue watching ────────────────────────────────────────────────────

    val recentlyWatched: StateFlow<List<WatchEntry>> =
        AppModule.watchHistoryStore.recentlyWatched()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Watch progress for embedded list-item bars ──────────────────────────
    //
    // Same pattern as LocalBrowserViewModel — we read the whole positions map
    // up front and refresh on (re)load so item composables can render the
    // sub-thumbnail progress line via an O(1) `positions[file.id]` lookup.

    private val positionStore = PlaybackPositionStore(AppModule.appContext)
    private val _positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val positions: StateFlow<Map<String, Long>> = _positions

    private fun refreshPositions() {
        _positions.value = positionStore.allPositions()
    }

    /** "LIST" or "GRID" — toggled by the top-bar icon and persisted to
     *  [com.driveplayer.data.SettingsStore.cloudBrowserViewMode] independently
     *  of the local tab. */
    val viewMode: StateFlow<String> =
        AppModule.settingsStore.cloudBrowserViewMode
            .stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")

    fun toggleViewMode() {
        viewModelScope.launch {
            AppModule.settingsStore.setCloudBrowserViewMode(
                if (viewMode.value == "GRID") "LIST" else "GRID"
            )
        }
    }

    // ── Folder thumbnail cache ─────────────────────────────────────────────
    //
    // Cloud folders carry no children in their metadata — to show a 2x2
    // collage we have to listFolder(folderId) once. Map keyed by folderId →
    // up to 4 child thumbnailLink URLs. Bounded with a soft cap so a user
    // that scrolls a 10k-folder Drive doesn't keep N HTTP responses pinned.
    private val _folderThumbnails = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val folderThumbnails: StateFlow<Map<String, List<String>>> = _folderThumbnails
    private val folderThumbnailsInFlight = mutableSetOf<String>()
    private val folderThumbCacheCap = 64

    /**
     * Lazily fetch up to 4 child video thumbnails for [folderId] for the
     * folder-collage UI. Idempotent: callers may invoke per-row in a
     * `LazyColumn`/`LazyVerticalGrid` and we'll only do one network round
     * trip per folder per session.
     */
    fun ensureFolderThumbnails(folderId: String) {
        if (_folderThumbnails.value.containsKey(folderId)) return
        if (!folderThumbnailsInFlight.add(folderId)) return
        viewModelScope.launch {
            val urls = repo.listFolder(folderId)
                .getOrNull()
                ?.asSequence()
                ?.filter { it.isVideo }
                ?.mapNotNull { it.thumbnailLink }
                ?.take(4)
                ?.toList()
                ?: emptyList()
            // Soft eviction: when the cache crosses the cap, drop the oldest
            // half. Drop is naive (insertion order from a HashMap is not
            // strictly LRU) but good enough for a thumbnail cache.
            val current = _folderThumbnails.value
            val next = if (current.size + 1 > folderThumbCacheCap) {
                current.entries.drop(current.size / 2).associate { it.toPair() }
            } else current
            _folderThumbnails.value = next + (folderId to urls)
            folderThumbnailsInFlight.remove(folderId)
        }
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    init {
        refreshPositions()
        loadCurrentFolder()
    }

    // ── Folder navigation ────────────────────────────────────────────────────

    fun switchTab(mode: TabMode) {
        if (_tabMode.value == mode) return
        _tabMode.value = mode
        _folderStack.value = listOf(
            if (mode == TabMode.MY_DRIVE) FolderEntry(null, "My Drive")
            else FolderEntry(null, "Shared with me")
        )
        loadCurrentFolder()
    }

    fun openFolder(folder: DriveFile) {
        _folderStack.value = _folderStack.value + FolderEntry(folder.id, folder.name)
        loadCurrentFolder()
    }

    fun navigateToPinnedFolder(pinned: PinnedFolder) {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchState.value = null
        val syntheticFile = DriveFile(
            id = pinned.id,
            name = pinned.name,
            mimeType = "application/vnd.google-apps.folder",
        )
        openFolder(syntheticFile)
    }

    fun goBack(): Boolean {
        if (_isSearchActive.value) {
            deactivateSearch()
            return true
        }
        val stack = _folderStack.value
        return if (stack.size > 1) {
            _folderStack.value = stack.dropLast(1)
            loadCurrentFolder()
            true
        } else false
    }

    fun refresh() {
        refreshPositions()
        loadCurrentFolder()
    }

    private fun loadCurrentFolder() {
        _state.value = BrowserState.Loading
        viewModelScope.launch {
            if (_tabMode.value == TabMode.MY_DRIVE) {
                repo.listFolder(currentFolder.id)
                    .onSuccess { _state.value = BrowserState.Success(it) }
                    .onFailure { _state.value = BrowserState.Error(it.message ?: "Unknown error") }
            } else {
                repo.getSharedFiles()
                    .onSuccess { _state.value = BrowserState.Success(it) }
                    .onFailure { _state.value = BrowserState.Error(it.message ?: "Unknown error") }
            }
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    fun activateSearch() {
        _isSearchActive.value = true
        _searchState.value = null
    }

    fun deactivateSearch() {
        searchDebounceJob?.cancel()
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchState.value = null
        inFlightQuery = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        val normalized = query.trim()

        // 1-character queries match almost every file in Drive (because
        // `name contains 'a'` is a word-prefix match) and are almost always
        // accidental — skip them entirely instead of round-tripping the
        // network just to cancel ourselves on the next keystroke.
        if (normalized.length < 2) {
            searchDebounceJob?.cancel()
            _searchState.value = null
            inFlightQuery = null
            return
        }

        // Coalesce duplicates: if the canonical query equals the one we
        // already scheduled / sent, do nothing. Critically, do NOT cancel
        // the existing job — otherwise a trailing-space edit would still
        // flap the network call.
        if (normalized == inFlightQuery) return

        searchDebounceJob?.cancel()
        inFlightQuery = normalized
        searchDebounceJob = viewModelScope.launch {
            delay(350)
            _searchState.value = BrowserState.Loading
            val result = repo.searchVideos(normalized)
            // Defensive guard: if the user has typed past this query (so we're
            // no longer the active in-flight) or the coroutine has been
            // cancelled, drop the result on the floor instead of painting a
            // stale Success/Error onto the UI. The repo already rethrows
            // CancellationException, but a result could arrive a hair before
            // the next setSearchQuery call cancels us.
            if (!isActive || inFlightQuery != normalized) return@launch
            result
                .onSuccess {
                    _searchState.value = BrowserState.Success(it)
                    // Only persist queries that actually matched something — saves
                    // the user from a recents list polluted with typos.
                    if (it.isNotEmpty()) {
                        AppModule.recentSearchStore.record(
                            RecentSearchStore.Namespace.CLOUD,
                            normalized,
                        )
                    }
                }
                .onFailure { e ->
                    // Belt-and-suspenders: a stray IOException("Canceled") from
                    // OkHttp should never reach the user. The repo's safeCall
                    // already rethrows CancellationException, so this is mostly
                    // a guard against the OkHttp-level cancel string.
                    if (e is CancellationException || e.isOkHttpCanceled()) {
                        if (inFlightQuery == normalized) inFlightQuery = null
                        return@launch
                    }
                    _searchState.value = BrowserState.Error(e.message ?: "Unknown error")
                    // Allow retry on the same query: clear the dedupe cache so
                    // re-typing (or backspace+retype) re-launches the request.
                    if (inFlightQuery == normalized) inFlightQuery = null
                }
        }
    }

    private fun Throwable.isOkHttpCanceled(): Boolean =
        this is java.io.IOException && message == "Canceled"

    /**
     * Refetches the parent folder of [file] so the player gets a sibling list
     * (used for external `.srt` auto-attach). Fast path returns the cached
     * folder content; falls back to an empty list on any error so the user
     * still gets to play the file even if the sibling fetch fails.
     */
    suspend fun fetchSiblingsFor(file: DriveFile): List<DriveFile> {
        val parentId = file.parents?.firstOrNull() ?: return emptyList()
        return repo.listFolder(parentId).getOrNull().orEmpty()
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            AppModule.recentSearchStore.remove(RecentSearchStore.Namespace.CLOUD, query)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            AppModule.recentSearchStore.clear(RecentSearchStore.Namespace.CLOUD)
        }
    }

    // ── Downloads ────────────────────────────────────────────────────────────

    val downloadedFileIds: StateFlow<Set<String>> =
        AppModule.downloadStore.downloads
            .map { list -> list.filter { it.status == DownloadStatus.COMPLETED }.map { it.fileId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val downloadingFileIds: StateFlow<Set<String>> =
        AppModule.downloadStore.downloads
            .map { list -> list.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }.map { it.fileId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun downloadFile(file: DriveFile) {
        viewModelScope.launch {
            // downloadManagerId = -1 sentinel: the DownloadService queue loop will
            // call dm.enqueue() when a download slot opens, respecting MAX_CONCURRENT.
            AppModule.downloadStore.save(
                DownloadEntry(
                    fileId = file.id,
                    title = file.name,
                    mimeType = file.mimeType,
                    downloadManagerId = -1L,
                    accessToken = accessToken,
                    status = DownloadStatus.QUEUED,
                    enqueuedAt = System.currentTimeMillis(),
                    thumbnailLink = file.thumbnailLink,
                )
            )
            // Wake the foreground service so the queue keeps advancing even if
            // the user immediately backgrounds or closes the app. Idempotent.
            DownloadService.start(AppModule.appContext)
        }
    }

    // ── Pin / unpin ──────────────────────────────────────────────────────────

    fun togglePin(folder: DriveFile) {
        viewModelScope.launch {
            val alreadyPinned = pinnedFolders.value.any { it.id == folder.id }
            if (alreadyPinned) AppModule.pinnedFolderStore.unpin(folder.id)
            else AppModule.pinnedFolderStore.pin(folder)
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(
        private val repo: DriveRepository,
        private val accessToken: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            FileBrowserViewModel(repo, accessToken) as T
    }
}
