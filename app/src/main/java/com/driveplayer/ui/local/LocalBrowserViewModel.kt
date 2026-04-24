package com.driveplayer.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.local.LocalVideoRepository
import com.driveplayer.data.local.VideoFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class LocalBrowserState {
    object Loading : LocalBrowserState()
    data class Folders(val folders: List<VideoFolder>) : LocalBrowserState()
    data class Videos(val folderName: String, val videos: List<LocalVideo>) : LocalBrowserState()
    data class Error(val message: String) : LocalBrowserState()
}

class LocalBrowserViewModel(private val repo: LocalVideoRepository) : ViewModel() {

    private val _state = MutableStateFlow<LocalBrowserState>(LocalBrowserState.Loading)
    val state: StateFlow<LocalBrowserState> = _state

    private val _isInFolder = MutableStateFlow(false)
    val isInFolder: StateFlow<Boolean> = _isInFolder

    private var currentFolderPath: String? = null

    init { loadFolders() }

    fun loadFolders() {
        _state.value = LocalBrowserState.Loading
        _isInFolder.value = false
        currentFolderPath = null
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
        viewModelScope.launch {
            repo.getVideosInFolder(folder.path)
                .onSuccess { _state.value = LocalBrowserState.Videos(folder.name, it) }
                .onFailure { _state.value = LocalBrowserState.Error(it.message ?: "Error") }
        }
    }

    fun goBack(): Boolean {
        return if (_isInFolder.value) {
            loadFolders()
            true
        } else false
    }

    fun refresh() {
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

    class Factory(private val repo: LocalVideoRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            LocalBrowserViewModel(repo) as T
    }
}
