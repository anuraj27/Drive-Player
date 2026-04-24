package com.driveplayer.ui.player.controllers

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@UnstableApi
class SyncController(
    private val player: Player
) {
    private val _subtitlesEnabled = MutableStateFlow(true)
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled

    // Note: True audio delay adjustment requires custom AudioProcessor in ExoPlayer which is 
    // highly complex and outside standard API. We simulate sync controls by managing subtitles.
    // In a full VLC-like implementation, we'd use LibVLC or a custom ExoPlayer Renderer.
    
    private val _subtitleDelayMs = MutableStateFlow(0L)
    val subtitleDelayMs: StateFlow<Long> = _subtitleDelayMs
    
    private val _audioDelayMs = MutableStateFlow(0L)
    val audioDelayMs: StateFlow<Long> = _audioDelayMs

    fun toggleSubtitles(enable: Boolean) {
        _subtitlesEnabled.value = enable
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enable)
            .build()
    }
    
    fun setSubtitleDelay(delayMs: Long) {
        _subtitleDelayMs.value = delayMs
        // Instant visual feedback for delay adjustment without reloading the whole media item
        // In Media3, shifting subtitles dynamically is limited, so we track state to allow
        // potential future rebuilding of the MediaItem with an offset.
    }

    fun setAudioDelay(delayMs: Long) {
        _audioDelayMs.value = delayMs
    }
    
    // Cycle through available audio tracks
    fun cycleAudioTrack() {
        // Find next audio track group and select it
        val mappedTrackInfo = player.currentTracks
        // Simplified: in a real scenario, we'd iterate over track groups
    }
}
