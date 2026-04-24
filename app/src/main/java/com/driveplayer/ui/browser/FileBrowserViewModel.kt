package com.driveplayer.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class BrowserState {
    object Loading : BrowserState()
    data class Success(val files: List<DriveFile>) : BrowserState()
    data class Error(val message: String) : BrowserState()
}

data class FolderEntry(val id: String?, val name: String)

class FileBrowserViewModel(private val repo: DriveRepository) : ViewModel() {

    private val _state = MutableStateFlow<BrowserState>(BrowserState.Loading)
    val state: StateFlow<BrowserState> = _state

    // Back-stack of folders for navigation
    private val _folderStack = MutableStateFlow<List<FolderEntry>>(
        listOf(FolderEntry(null, "My Drive"))
    )
    val folderStack: StateFlow<List<FolderEntry>> = _folderStack

    val currentFolder: FolderEntry
        get() = _folderStack.value.last()

    init { loadCurrentFolder() }

    fun openFolder(folder: DriveFile) {
        _folderStack.value = _folderStack.value + FolderEntry(folder.id, folder.name)
        loadCurrentFolder()
    }

    fun goBack(): Boolean {
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
            repo.listFolder(currentFolder.id)
                .onSuccess { _state.value = BrowserState.Success(it) }
                .onFailure { _state.value = BrowserState.Error(it.message ?: "Unknown error") }
        }
    }

    class Factory(private val repo: DriveRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            FileBrowserViewModel(repo) as T
    }
}
