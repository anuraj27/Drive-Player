package com.driveplayer.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.ui.player.controllers.DisplayController
import com.driveplayer.ui.player.controllers.PlayerController
import com.driveplayer.ui.player.controllers.SyncController
import okhttp3.OkHttpClient

@UnstableApi
class PlayerViewModel(
    context: Context,
    repo: DriveRepository?,
    okHttpClient: OkHttpClient?,
    videoFile: DriveFile?,
    siblingFiles: List<DriveFile>,
    localVideo: LocalVideo?,
) : ViewModel() {

    val playerController = PlayerController(context, repo, okHttpClient, viewModelScope, AppModule.watchHistoryStore)
    val syncController = SyncController(playerController.player, viewModelScope)
    val displayController = DisplayController()

    init {
        if (localVideo != null) {
            // Local video playback — direct URI
            playerController.prepareAndPlayLocal(localVideo)
        } else if (videoFile != null) {
            // Cloud video playback — Drive stream
            val srtFile = siblingFiles.firstOrNull {
                it.isSrt && it.name.removeSuffix(".srt").equals(
                    videoFile.name.substringBeforeLast('.'), ignoreCase = true
                )
            } ?: siblingFiles.firstOrNull { it.isSrt }

            playerController.prepareAndPlay(videoFile, srtFile)
        }
    }

    override fun onCleared() {
        playerController.player.stop()
        playerController.release()
        super.onCleared()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val context: Context,
        private val repo: DriveRepository?,
        private val okHttpClient: OkHttpClient?,
        private val videoFile: DriveFile?,
        private val siblingFiles: List<DriveFile>,
        private val localVideo: LocalVideo? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            PlayerViewModel(context, repo, okHttpClient, videoFile, siblingFiles, localVideo) as T
    }
}
