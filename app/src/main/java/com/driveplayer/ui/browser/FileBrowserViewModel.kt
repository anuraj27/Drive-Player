package com.driveplayer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.player.DownloadEntry
import com.driveplayer.player.DownloadStatus
import com.driveplayer.player.PinnedFolder
import com.driveplayer.player.WatchEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    // ── Pinned folders ───────────────────────────────────────────────────────

    val pinnedFolders: StateFlow<List<PinnedFolder>> =
        AppModule.pinnedFolderStore.pinnedFolders
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Continue watching ────────────────────────────────────────────────────

    val recentlyWatched: StateFlow<List<WatchEntry>> =
        AppModule.watchHistoryStore.recentlyWatched()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Init ─────────────────────────────────────────────────────────────────

    init { loadCurrentFolder() }

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

    fun refresh() = loadCurrentFolder()

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
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchDebounceJob?.cancel()
        if (query.isBlank()) {
            _searchState.value = null
            return
        }
        searchDebounceJob = viewModelScope.launch {
            delay(350)
            _searchState.value = BrowserState.Loading
            repo.searchVideos(query)
                .onSuccess { _searchState.value = BrowserState.Success(it) }
                .onFailure { _searchState.value = BrowserState.Error(it.message ?: "Unknown error") }
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
            // downloadManagerId = -1 sentinel: DownloadsViewModel.pollAndAdvanceQueue()
            // will call dm.enqueue() when a download slot opens, respecting MAX_CONCURRENT.
            AppModule.downloadStore.save(
                DownloadEntry(
                    fileId = file.id,
                    title = file.name,
                    mimeType = file.mimeType,
                    downloadManagerId = -1L,
                    accessToken = accessToken,
                    status = DownloadStatus.QUEUED,
                    enqueuedAt = System.currentTimeMillis(),
                )
            )
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
