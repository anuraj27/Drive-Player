package com.driveplayer.ui.player.controllers

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.player.DriveDataSourceFactory
import com.driveplayer.player.PlaybackPositionStore
import com.driveplayer.player.WatchEntry
import com.driveplayer.player.WatchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@UnstableApi
class PlayerController(
    private val context: Context,
    private val repo: DriveRepository?,
    private val okHttpClient: OkHttpClient?,
    private val scope: CoroutineScope,
    private val watchHistoryStore: WatchHistoryStore? = null,
) {
    val player: ExoPlayer

    private val positionStore = PlaybackPositionStore(context)
    private var currentVideoFile: DriveFile? = null
    private var hasSeekedToSavedPosition = false

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _abLoopStart = MutableStateFlow(0L)
    val abLoopStart: StateFlow<Long> = _abLoopStart

    private val _abLoopEnd = MutableStateFlow(0L)
    val abLoopEnd: StateFlow<Long> = _abLoopEnd

    private var isLoopingSegment = false
    @Volatile private var loopStartMs = 0L
    @Volatile private var loopEndMs = 0L

    init {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 2_000, 5_000)
            .build()

        val builder = ExoPlayer.Builder(context)
        if (okHttpClient != null) {
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(DriveDataSourceFactory(okHttpClient)))
        }
        player = builder
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _error.value = "Playback error: ${error.message}"
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                _duration.value = player.duration.coerceAtLeast(0L)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = playbackState == Player.STATE_BUFFERING
                when (playbackState) {
                    Player.STATE_READY -> {
                        _duration.value = player.duration.coerceAtLeast(0L)
                        if (!hasSeekedToSavedPosition) {
                            hasSeekedToSavedPosition = true
                            val saved = positionStore.get(currentVideoFile?.id ?: return)
                            if (saved > 5_000L) player.seekTo(saved)
                        }
                    }
                    Player.STATE_ENDED -> currentVideoFile?.let { file ->
                        positionStore.clear(file.id)
                        scope.launch { watchHistoryStore?.clear(file.id) }
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })

        scope.launch {
            var lastSaveMs = 0L
            while (isActive) {
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    _currentPosition.value = pos
                    _bufferedPosition.value = player.bufferedPosition
                    if (pos - lastSaveMs >= 5_000L) {
                        currentVideoFile?.let { file ->
                            positionStore.save(file.id, pos)
                            val dur = player.duration
                            if (dur > 0L) {
                                scope.launch {
                                    watchHistoryStore?.save(
                                        WatchEntry(
                                            fileId = file.id,
                                            title = file.name,
                                            mimeType = file.mimeType,
                                            thumbnailLink = file.thumbnailLink,
                                            positionMs = pos,
                                            durationMs = dur,
                                            lastWatchedAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                        }
                        lastSaveMs = pos
                    }
                    if (isLoopingSegment && loopEndMs > 0 && pos >= loopEndMs) {
                        player.seekTo(loopStartMs)
                    }
                    delay(200)
                } else {
                    delay(500)
                }
            }
        }
    }

    fun prepareAndPlay(videoFile: DriveFile, subtitleFile: DriveFile?) {
        if (repo == null) return
        currentVideoFile = videoFile
        hasSeekedToSavedPosition = false

        scope.launch {
            val mediaItemBuilder = MediaItem.Builder()
                .setUri(repo.streamUrl(videoFile.id))
                .setMimeType(videoFile.mimeType)

            if (subtitleFile != null) {
                mediaItemBuilder.setSubtitleConfigurations(
                    listOf(
                        MediaItem.SubtitleConfiguration
                            .Builder(Uri.parse(repo.srtUrl(subtitleFile.id)))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                )
            }

            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * Prepares and plays a local device video by content URI.
     */
    fun prepareAndPlayLocal(localVideo: LocalVideo) {
        currentVideoFile = null
        hasSeekedToSavedPosition = false

        scope.launch {
            val mediaItem = MediaItem.Builder()
                .setUri(localVideo.uri)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun loadExternalSubtitle(subtitleUri: Uri) {
        val position = player.currentPosition
        val current = player.currentMediaItem ?: return
        val newItem = current.buildUpon()
            .setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
            .build()
        player.setMediaItem(newItem, position)
        player.prepare()
        player.play()
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun seekTo(position: Long) {
        val maxDuration = player.duration
        val targetPosition = if (maxDuration > 0L) {
            position.coerceIn(0L, maxDuration)
        } else {
            position.coerceAtLeast(0L)
        }
        player.seekTo(targetPosition)
        _currentPosition.value = player.currentPosition
    }

    fun seekBy(offsetMs: Long) = seekTo(player.currentPosition + offsetMs)

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        player.setPlaybackSpeed(speed)
    }

    fun setLooping(repeat: Boolean) {
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setLoopStart() {
        _abLoopStart.value = player.currentPosition
        if (_abLoopEnd.value > _abLoopStart.value) activateABLoop()
    }

    fun setLoopEnd() {
        _abLoopEnd.value = player.currentPosition
        if (_abLoopStart.value in 1 until _abLoopEnd.value) activateABLoop()
    }

    fun clearABLoop() {
        isLoopingSegment = false
        loopStartMs = 0L
        loopEndMs = 0L
        _abLoopStart.value = 0L
        _abLoopEnd.value = 0L
    }

    fun retryPlayback() {
        _error.value = null
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            hasSeekedToSavedPosition = false
            player.prepare()
        }
        player.playWhenReady = true
    }

    private fun activateABLoop() {
        isLoopingSegment = true
        loopStartMs = _abLoopStart.value
        loopEndMs = _abLoopEnd.value
    }

    fun release() {
        player.stop()
        player.release()
    }
}
