package com.driveplayer.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.player.DriveDataSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@UnstableApi
class PlayerViewModel(
    context: Context,
    private val repo: DriveRepository,
    private val okHttpClient: OkHttpClient,
    private val videoFile: DriveFile,
    private val siblingFiles: List<DriveFile>,  // files in same folder, for SRT lookup
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    val player: ExoPlayer = buildPlayer(context)

    init {
        buildAndPlayMediaItem()
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val dataSourceFactory = DriveDataSourceFactory(okHttpClient)
        
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                32000, // minBufferMs
                64000, // maxBufferMs
                2500,  // bufferForPlaybackMs
                5000   // bufferForPlaybackAfterRebufferMs
            ).build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.NEXT_SYNC)
            .build()
            .also { player ->
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _error.value = "Playback error: ${error.message}"
                    }
                })
                player.playWhenReady = true
            }
    }

    private fun buildAndPlayMediaItem() {
        viewModelScope.launch {
            val videoUrl   = repo.streamUrl(videoFile.id)

            // Find a matching .srt file in the same folder
            // Match heuristic: same base name, or the only .srt in the folder
            val srtFile = siblingFiles.firstOrNull { it.isSrt &&
                it.name.removeSuffix(".srt").equals(
                    videoFile.name.substringBeforeLast('.'), ignoreCase = true
                )
            } ?: siblingFiles.firstOrNull { it.isSrt }

            val mediaItemBuilder = MediaItem.Builder()
                .setUri(videoUrl)
                .setMimeType(videoFile.mimeType)

            if (srtFile != null) {
                val srtUrl = repo.srtUrl(srtFile.id)
                mediaItemBuilder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(srtUrl))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }

            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    fun toggleSubtitles(enable: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !enable)
            .build()
    }

    override fun onCleared() {
        player.release()
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
