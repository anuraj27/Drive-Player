package com.driveplayer.ui.player.controllers

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@UnstableApi
class SyncController(private val player: Player) {

    private val _subtitlesEnabled = MutableStateFlow(true)
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled

    private val _audioTracks = MutableStateFlow<List<String>>(emptyList())
    val audioTracks: StateFlow<List<String>> = _audioTracks

    private val _selectedAudioTrack = MutableStateFlow(0)
    val selectedAudioTrack: StateFlow<Int> = _selectedAudioTrack

    private val _availableSubtitleTracks = MutableStateFlow<List<String>>(emptyList())
    val availableSubtitleTracks: StateFlow<List<String>> = _availableSubtitleTracks

    private val _selectedSubtitleTrack = MutableStateFlow(-1)
    val selectedSubtitleTrack: StateFlow<Int> = _selectedSubtitleTrack

    private val _audioDelay = MutableStateFlow(0f)
    val audioDelay: StateFlow<Float> = _audioDelay

    private val _subtitleDelay = MutableStateFlow(0f)
    val subtitleDelay: StateFlow<Float> = _subtitleDelay

    private val _subtitlePosition = MutableStateFlow("Bottom")
    val subtitlePosition: StateFlow<String> = _subtitlePosition

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                refreshAudioTracks(tracks)
                refreshSubtitleTracks(tracks)
            }
        })
    }

    fun toggleSubtitles(enable: Boolean) {
        _subtitlesEnabled.value = enable
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enable)
            .build()
    }

    fun selectAudioTrack(index: Int) {
        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (index !in audioGroups.indices) return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(audioGroups[index].mediaTrackGroup, 0))
            .build()
        _selectedAudioTrack.value = index
    }

    fun selectSubtitleTrack(index: Int) {
        val subtitleGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (index !in subtitleGroups.indices) return
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .addOverride(TrackSelectionOverride(subtitleGroups[index].mediaTrackGroup, 0))
            .build()
        _selectedSubtitleTrack.value = index
    }

    fun setAudioDelay(delay: Float) {
        _audioDelay.value = delay
        // Note: Real audio delay is complex in ExoPlayer without custom AudioProcessors.
        // This sets the UI state. A full ExoPlayer implementation would pipe this into the renderer.
    }

    fun setSubtitleDelay(delay: Float) {
        _subtitleDelay.value = delay
        // Note: Subtitle delay works differently for embedded vs external in ExoPlayer.
    }

    fun setSubtitlePosition(position: String) {
        _subtitlePosition.value = position
    }

    private fun refreshAudioTracks(tracks: Tracks) {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        _audioTracks.value = groups.mapIndexed { index, group ->
            val format = group.getTrackFormat(0)
            format.language
                ?.let { runCatching { Locale(it).displayLanguage }.getOrNull() }
                ?.takeIf { it.isNotBlank() && it.any { c -> c.isUpperCase() } }
                ?: "Track ${index + 1}"
        }
        _selectedAudioTrack.value = groups.indexOfFirst { it.isSelected }.coerceAtLeast(0)
    }

    private fun refreshSubtitleTracks(tracks: Tracks) {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        _availableSubtitleTracks.value = groups.mapIndexed { index, group ->
            val format = group.getTrackFormat(0)
            format.language
                ?.let { runCatching { Locale(it).displayLanguage }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it ${if (format.label != null) "(${format.label})" else ""}".trim() }
                ?: "Subtitle ${index + 1}"
        }
        _selectedSubtitleTrack.value = groups.indexOfFirst { it.isSelected }
    }
}
