package com.driveplayer.ui.player.controllers

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.player.DriveDataSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@UnstableApi
class PlayerController(
    private val context: Context,
    private val repo: DriveRepository,
    private val okHttpClient: OkHttpClient,
    private val scope: CoroutineScope
) {
    val player: ExoPlayer

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration
    
    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private var currentVideoFile: DriveFile? = null
    private var currentSubtitleFile: DriveFile? = null
    private var isLoopingSegment = false
    private var loopStart = 0L
    private var loopEnd = 0L

    init {
        val dataSourceFactory = DriveDataSourceFactory(okHttpClient)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                32000, 64000, 2500, 5000
            ).build()

        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.NEXT_SYNC)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _error.value = "Playback error: ${error.message}"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    _duration.value = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })

        // Position tracker
        scope.launch {
            while (true) {
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    _currentPosition.value = pos
                    
                    // A-B Loop Logic
                    if (isLoopingSegment && loopEnd > 0 && pos >= loopEnd) {
                        player.seekTo(loopStart)
                    }
                }
                delay(200) // 5fps update
            }
        }
    }

    fun prepareAndPlay(videoFile: DriveFile, subtitleFile: DriveFile?) {
        this.currentVideoFile = videoFile
        this.currentSubtitleFile = subtitleFile
        
        scope.launch {
            val videoUrl = repo.streamUrl(videoFile.id)
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(videoUrl)
                .setMimeType(videoFile.mimeType)

            if (subtitleFile != null) {
                val srtUrl = repo.srtUrl(subtitleFile.id)
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
            player.playWhenReady = true
        }
    }

    fun play() = player.play()
    fun pause() = player.pause()
    fun seekTo(position: Long) {
        player.seekTo(position.coerceIn(0, player.duration))
        _currentPosition.value = player.currentPosition
    }
    
    fun seekBy(offset: Long) {
        seekTo(player.currentPosition + offset)
    }

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        player.setPlaybackSpeed(speed)
    }
    
    fun setLooping(repeat: Boolean) {
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setABLoop(start: Long, end: Long) {
        isLoopingSegment = true
        loopStart = start
        loopEnd = end
    }

    fun clearABLoop() {
        isLoopingSegment = false
    }

    fun release() {
        player.release()
    }
}
