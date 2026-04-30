package com.driveplayer.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.local.LocalVideoRepository
import com.driveplayer.data.local.VideoFolder
import com.driveplayer.di.AppModule
import com.driveplayer.player.PlaybackPositionStore
import com.driveplayer.player.RecentSearchStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class LocalBrowserState {
    object Loading : LocalBrowserState()
    data class Folders(val folders: List<VideoFolder>) : LocalBrowserState()
    data class Videos(val folderName: String, val videos: List<LocalVideo>) : LocalBrowserState()
    data class Error(val message: String) : LocalBrowserState()
}

/** Search state is independent of [LocalBrowserState] so the underlying folder
 *  view doesn't have to be torn down each time the user types. */
sealed class LocalSearchState {
    object Loading : LocalSearchState()
    data class Success(val videos: List<LocalVideo>) : LocalSearchState()
    data class Error(val message: String) : LocalSearchState()
}

class LocalBrowserViewModel(private val repo: LocalVideoRepository) : ViewModel() {

    // ── Folder browsing ──────────────────────────────────────────────────────

    private val _state = MutableStateFlow<LocalBrowserState>(LocalBrowserState.Loading)
    val state: StateFlow<LocalBrowserState> = _state

    private val _isInFolder = MutableStateFlow(false)
    val isInFolder: StateFlow<Boolean> = _isInFolder

    private var currentFolderPath: String? = null

    // ── Search ───────────────────────────────────────────────────────────────

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchState = MutableStateFlow<LocalSearchState?>(null)
    val searchState: StateFlow<LocalSearchState?> = _searchState

    private var searchDebounceJob: Job? = null

    /** Last canonical query already scheduled or in flight — see the equivalent
     *  field in FileBrowserViewModel for rationale. Even though the local
     *  search runs in-process (no network), the MediaStore scan + flat filter
     *  on a large library is non-trivial and we shouldn't redo it just because
     *  a trailing-space edit re-emitted the field state. */
    private var inFlightQuery: String? = null

    val recentSearches: StateFlow<List<String>> =
        AppModule.recentSearchStore.recents(RecentSearchStore.Namespace.LOCAL)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Bulk snapshot of every saved playback position, refreshed each time we
     * (re)load the folder/video list. Item composables read this map keyed by
     * `local_<id>` (or whatever the screen passes) to render the embedded
     * watch-progress bar — a single read instead of N SharedPreferences hits
     * during recomposition.
     *
     * Not reactive (SharedPreferences has no built-in flow), but the player
     * writes positions only every 5s while playing and the user can't see
     * the browse screen and the player at the same time. We refresh on
     * every (re)load and on `refresh()`, which covers every realistic case.
     */
    private val positionStore = PlaybackPositionStore(AppModule.appContext)
    private val _positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val positions: StateFlow<Map<String, Long>> = _positions

    /** "LIST" or "GRID" — driven by the toggle button in the top bar and
     *  persisted to [com.driveplayer.data.SettingsStore.localBrowserViewMode]
     *  so the user's preference survives process death. */
    val viewMode: StateFlow<String> =
        AppModule.settingsStore.localBrowserViewMode
            .stateIn(viewModelScope, SharingStarted.Eagerly, "LIST")

    fun toggleViewMode() {
        viewModelScope.launch {
            AppModule.settingsStore.setLocalBrowserViewMode(
                if (viewMode.value == "GRID") "LIST" else "GRID"
            )
        }
    }

    init {
        refreshPositions()
        loadFolders()
    }

    private fun refreshPositions() {
        _positions.value = positionStore.allPositions()
    }

    fun loadFolders() {
        _state.value = LocalBrowserState.Loading
        _isInFolder.value = false
        currentFolderPath = null
        refreshPositions()
        viewModelScope.launch {
            repo.getVideoFolders()
                .onSuccess { _state.value = LocalBrowserState.Folders(it) }
                .onFailure { _state.value = LocalBrowserState.Error(it.message ?: "Failed to scan videos") }
        }
    }

    fun openFolder(folder: VideoFolder) {
        _state.value = LocalBrowserState.Loading
        _isInFolder.value = true
        currentFolderPath = folder.path
        refreshPositions()
        viewModelScope.launch {
            repo.getVideosInFolder(folder.path)
                .onSuccess { _state.value = LocalBrowserState.Videos(folder.name, it) }
                .onFailure { _state.value = LocalBrowserState.Error(it.message ?: "Error") }
        }
    }

    fun goBack(): Boolean {
        if (_isSearchActive.value) {
            deactivateSearch()
            return true
        }
        return if (_isInFolder.value) {
            loadFolders()
            true
        } else false
    }

    fun refresh() {
        refreshPositions()
        val path = currentFolderPath
        if (path != null && _isInFolder.value) {
            _state.value = LocalBrowserState.Loading
            viewModelScope.launch {
                repo.getVideosInFolder(path)
                    .onSuccess { vids ->
                        _state.value = LocalBrowserState.Videos(
                            vids.firstOrNull()?.folderName ?: "Videos", vids
                        )
                    }
                    .onFailure { _state.value = LocalBrowserState.Error(it.message ?: "Error") }
            }
        } else {
            loadFolders()
        }
    }

    // ── Search actions ───────────────────────────────────────────────────────

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

        if (normalized.length < 2) {
            searchDebounceJob?.cancel()
            _searchState.value = null
            inFlightQuery = null
            return
        }

        if (normalized == inFlightQuery) return

        searchDebounceJob?.cancel()
        inFlightQuery = normalized
        searchDebounceJob = viewModelScope.launch {
            // Same 250 ms debounce as the cloud search — local is faster but
            // MediaStore queries on huge libraries can still be measurable, and
            // matching in-memory + recomposition can ripple costs upstream.
            delay(250)
            _searchState.value = LocalSearchState.Loading
            val result = repo.searchVideos(normalized)
            // See FileBrowserViewModel for rationale: drop results from a
            // cancelled / superseded job so we don't paint stale state.
            if (!isActive || inFlightQuery != normalized) return@launch
            result
                .onSuccess {
                    _searchState.value = LocalSearchState.Success(it)
                    if (it.isNotEmpty()) {
                        AppModule.recentSearchStore.record(
                            RecentSearchStore.Namespace.LOCAL,
                            normalized,
                        )
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) {
                        if (inFlightQuery == normalized) inFlightQuery = null
                        return@launch
                    }
                    _searchState.value = LocalSearchState.Error(e.message ?: "Search failed")
                    if (inFlightQuery == normalized) inFlightQuery = null
                }
        }
    }

    fun removeRecentSearch(query: String) {
        viewModelScope.launch {
            AppModule.recentSearchStore.remove(RecentSearchStore.Namespace.LOCAL, query)
        }
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            AppModule.recentSearchStore.clear(RecentSearchStore.Namespace.LOCAL)
        }
    }

    class Factory(private val repo: LocalVideoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            LocalBrowserViewModel(repo) as T
    }
}
