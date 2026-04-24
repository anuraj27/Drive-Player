package com.driveplayer.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.ui.player.controllers.DisplayController
import com.driveplayer.ui.player.controllers.PlayerController
import com.driveplayer.ui.player.controllers.SyncController
import okhttp3.OkHttpClient

@UnstableApi
class PlayerViewModel(
    context: Context,
    repo: DriveRepository,
    okHttpClient: OkHttpClient,
    videoFile: DriveFile,
    siblingFiles: List<DriveFile>
) : ViewModel() {

    val playerController = PlayerController(context, repo, okHttpClient, viewModelScope)
    val syncController = SyncController(playerController.player)
    val displayController = DisplayController()

    init {
        // Find a matching .srt file in the same folder
        val srtFile = siblingFiles.firstOrNull { 
            it.isSrt && it.name.removeSuffix(".srt").equals(
                videoFile.name.substringBeforeLast('.'), ignoreCase = true
            )
        } ?: siblingFiles.firstOrNull { it.isSrt }

        playerController.prepareAndPlay(videoFile, srtFile)
    }

    override fun onCleared() {
        playerController.release()
        super.onCleared()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val context: Context,
        private val repo: DriveRepository,
        private val okHttpClient: OkHttpClient,
        private val videoFile: DriveFile,
        private val siblingFiles: List<DriveFile>,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            PlayerViewModel(context, repo, okHttpClient, videoFile, siblingFiles) as T
    }
}
