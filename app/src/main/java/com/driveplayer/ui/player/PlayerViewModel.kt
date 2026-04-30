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
    videoFile: DriveFile?,
    siblingFiles: List<DriveFile>,
    localVideo: LocalVideo?,
) : ViewModel() {

    val playerController = PlayerController(
        context = context,
        repo = repo,
        scope = viewModelScope,
        watchHistoryStore = AppModule.watchHistoryStore,
    )
    val syncController = SyncController(
        playerController = playerController,
        scope = viewModelScope,
        defaultSubtitleScalePercent = playerController.settingsSnapshot.defaultSubtitleScale,
        defaultSubtitleColorRgb = playerController.settingsSnapshot.defaultSubtitleColor,
        defaultSubtitleBgAlpha255 = playerController.settingsSnapshot.defaultSubtitleBgAlpha,
    )
    val displayController = DisplayController()

    init {
        // Receive per-file restore events from PlayerController and forward the
        // delay values into SyncController so the in-player Audio/Subtitle
        // panel sliders show the restored offsets. Track-id and rate restore
        // happen inside PlayerController (it owns the MediaPlayer) — they don't
        // need to round-trip through here. Conversion: state stores micro-
        // seconds; SyncController exposes seconds.
        playerController.onStateRestored = { state ->
            syncController.setAudioDelay(state.audioDelayUs / 1_000_000f)
            syncController.setSubtitleDelay(state.subtitleDelayUs / 1_000_000f)
        }
    }

    // Hold inputs so PlayerScreen can trigger playback AFTER attachViews has run.
    // Calling play() before the surface is attached causes libVLC's vout to fail
    // ("can't get Video Surface" / "video output creation failed") and never recover.
    private val pendingLocalVideo = localVideo
    private val pendingVideoFile = videoFile
    private val pendingSiblingFiles = siblingFiles

    @Volatile private var hasStarted = false
    fun startPlaybackOnce() {
        if (hasStarted) return
        hasStarted = true
        if (pendingLocalVideo != null) {
            playerController.prepareAndPlayLocal(pendingLocalVideo)
        } else if (pendingVideoFile != null) {
            // Auto-attach an external `.srt` from the same Drive folder unless
            // the user disabled it in Settings → Subtitles. Their saved
            // selection in the Subtitle panel still wins, but the *initial*
            // load skips the .srt slave entirely so a heavy/wrong sub never
            // even reaches libVLC.
            val srtFile = if (playerController.settingsSnapshot.autoLoadSubtitles) {
                pendingSiblingFiles.firstOrNull {
                    it.isSrt && it.name.removeSuffix(".srt").equals(
                        pendingVideoFile.name.substringBeforeLast('.'), ignoreCase = true
                    )
                } ?: pendingSiblingFiles.firstOrNull { it.isSrt }
            } else null
            playerController.prepareAndPlay(pendingVideoFile, srtFile)
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
        // Kept on the API to avoid disrupting AppNavigation; the token now flows through
        // AppModule's active credentials (set in CloudViewModel.connectWith).
        @Suppress("UNUSED_PARAMETER") accessToken: String? = null,
        private val videoFile: DriveFile?,
        private val siblingFiles: List<DriveFile>,
        private val localVideo: LocalVideo? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>) =
            PlayerViewModel(context, repo, videoFile, siblingFiles, localVideo) as T
    }
}
