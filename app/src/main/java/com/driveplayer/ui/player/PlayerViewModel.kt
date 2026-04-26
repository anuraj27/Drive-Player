package com.driveplayer.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.ui.player.controllers.DisplayController
import com.driveplayer.ui.player.controllers.PlayerController
import com.driveplayer.ui.player.controllers.SyncController

class PlayerViewModel(
    context: Context,
    repo: DriveRepository?,
    accessToken: String?,
    videoFile: DriveFile?,
    siblingFiles: List<DriveFile>,
    localVideo: LocalVideo?,
) : ViewModel() {

    val playerController = PlayerController(
        context = context,
        accessToken = accessToken,
        repo = repo,
        scope = viewModelScope,
        watchHistoryStore = AppModule.watchHistoryStore,
    )
    val syncController = SyncController(playerController.mediaPlayer, viewModelScope)
    val displayController = DisplayController()

    init {
        if (localVideo != null) {
            playerController.prepareAndPlayLocal(localVideo)
        } else if (videoFile != null) {
            val srtFile = siblingFiles.firstOrNull {
                it.isSrt && it.name.removeSuffix(".srt").equals(
                    videoFile.name.substringBeforeLast('.'), ignoreCase = true
                )
            } ?: siblingFiles.firstOrNull { it.isSrt }
            playerController.prepareAndPlay(videoFile, srtFile)
        }
    }

    override fun onCleared() {
        syncController.cancel()
        playerController.release()
        super.onCleared()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val context: Context,
        private val repo: DriveRepository?,
        private val accessToken: String?,
        private val videoFile: DriveFile?,
        private val siblingFiles: List<DriveFile>,
        private val localVideo: LocalVideo? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            PlayerViewModel(context, repo, accessToken, videoFile, siblingFiles, localVideo) as T
    }
}
