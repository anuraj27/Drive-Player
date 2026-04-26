package com.driveplayer.ui.player.controllers

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
class SyncController(private val player: Player, private val scope: CoroutineScope) {

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

    private val _subtitleSize = MutableStateFlow(16f)  // SP units — orientation-independent
    val subtitleSize: StateFlow<Float> = _subtitleSize

    // Stored as ARGB Long (e.g. 0xFFFFFFFF for white) — toInt() wraps correctly for Android color ints.
    private val _subtitleTextColor = MutableStateFlow(0xFFFFFFFFL)
    val subtitleTextColor: StateFlow<Long> = _subtitleTextColor

    private val _subtitleBgAlpha = MutableStateFlow(0.5f)
    val subtitleBgAlpha: StateFlow<Float> = _subtitleBgAlpha

    // Current cues to render — managed by onCues + optional delay coroutine.
    private val _activeCues = MutableStateFlow<List<Cue>>(emptyList())
    val activeCues: StateFlow<List<Cue>> = _activeCues

    // Cancels the previous delayed-display job when a new cue or seek arrives.
    private var pendingCueJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                refreshAudioTracks(tracks)
                refreshSubtitleTracks(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                if (!_subtitlesEnabled.value) return
                pendingCueJob?.cancel()
                if (cueGroup.cues.isEmpty()) {
                    _activeCues.value = emptyList()
                    return
                }
                val delayMs = (_subtitleDelay.value * 1_000f).toLong()
                if (delayMs <= 0L) {
                    // No delay — display immediately.
                    _activeCues.value = cueGroup.cues
                } else {
                    // Positive delay: schedule display after delayMs.
                    // Cancelling pendingCueJob on next cue/seek prevents stale cues surfacing.
                    pendingCueJob = scope.launch {
                        delay(delayMs)
                        if (isActive) _activeCues.value = cueGroup.cues
                    }
                }
            }
        })
    }

    fun toggleSubtitles(enable: Boolean) {
        _subtitlesEnabled.value = enable
        if (!enable) {
            pendingCueJob?.cancel()
            _activeCues.value = emptyList()
        }
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
        try {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                // Re-enable text tracks in case they were disabled via toggleSubtitles(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .addOverride(TrackSelectionOverride(subtitleGroups[index].mediaTrackGroup, 0))
                .build()
            _subtitlesEnabled.value = true
            _selectedSubtitleTrack.value = index
        } catch (_: Exception) {
            // TrackGroup reference became stale between UI render and tap; onTracksChanged will resync
        }
    }

    fun setAudioDelay(delay: Float) {
        _audioDelay.value = delay
    }

    fun setSubtitleDelay(delay: Float) {
        _subtitleDelay.value = delay
    }

    fun setSubtitlePosition(position: String) {
        _subtitlePosition.value = position
    }

    fun setSubtitleSize(size: Float) { _subtitleSize.value = size }
    fun setSubtitleTextColor(color: Long) { _subtitleTextColor.value = color }
    fun setSubtitleBgAlpha(alpha: Float) { _subtitleBgAlpha.value = alpha }

    private fun refreshAudioTracks(tracks: Tracks) {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        _audioTracks.value = groups.mapIndexed { index, group ->
            val format = group.getTrackFormat(0)
            buildString {
                val langName = format.language
                    ?.let { runCatching { Locale(it).displayLanguage }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                when {
                    langName != null -> append(langName)
                    !format.label.isNullOrBlank() -> append(format.label)
                    else -> append("Track ${index + 1}")
                }
                // Append channel layout and label (e.g. "English · Stereo · Commentary")
                val channelStr = when (format.channelCount) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> if (format.channelCount > 0) "${format.channelCount}ch" else null
                }
                val extras = listOfNotNull(
                    channelStr,
                    format.label?.takeIf { langName != null && it.isNotBlank() }
                )
                if (extras.isNotEmpty()) append(" · ${extras.joinToString(" · ")}")
            }
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
