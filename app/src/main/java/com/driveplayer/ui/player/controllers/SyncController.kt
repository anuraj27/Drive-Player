package com.driveplayer.ui.player.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer

/**
 * Drives audio/subtitle track listings, selection, and per-track delays through libVLC.
 *
 * libVLC differences from the previous ExoPlayer implementation:
 *  - Track IDs are `Int` (not opaque TrackGroup references) — much simpler to wire.
 *  - Subtitle delay is a native MediaPlayer property (microseconds), no manual coroutine
 *    queue needed. We just call `setSpuDelay`.
 *  - Cue interception is gone — VLC renders subtitles directly to its own surface.
 *  - Subtitle appearance (size/color/bg) is set globally via LibVLC options at construction;
 *    those sliders no longer mutate state at runtime (kept on the API for UI compatibility).
 */
class SyncController(
    private val mediaPlayer: MediaPlayer,
    private val scope: CoroutineScope,
) {

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

    // Kept on the API surface for the existing SubtitlePanel UI — not applied dynamically
    // under libVLC (would require per-Media options + restart).
    private val _subtitleSize = MutableStateFlow(16f)
    val subtitleSize: StateFlow<Float> = _subtitleSize
    private val _subtitleTextColor = MutableStateFlow(0xFFFFFFFFL)
    val subtitleTextColor: StateFlow<Long> = _subtitleTextColor
    private val _subtitleBgAlpha = MutableStateFlow(0.5f)
    val subtitleBgAlpha: StateFlow<Float> = _subtitleBgAlpha

    // Maps the audio/subtitle track-list index (UI) back to libVLC's track ID (transport).
    // libVLC's `audioTrack`/`spuTrack` setters expect IDs, not indices.
    private var audioTrackIds: List<Int> = emptyList()
    private var subtitleTrackIds: List<Int> = emptyList()

    // Periodic refresh — libVLC doesn't fire a "tracks ready" event we can hook reliably,
    // so we poll until tracks become available, then back off.
    private var trackRefreshJob: Job? = null

    init {
        trackRefreshJob = scope.launch {
            while (true) {
                refreshTracks()
                // Faster polling at first; slow down once we have tracks.
                delay(if (_audioTracks.value.isEmpty() && _availableSubtitleTracks.value.isEmpty()) 500L else 2_000L)
            }
        }
    }

    private fun refreshTracks() {
        val audio = mediaPlayer.audioTracks
        if (audio != null) {
            // VLC includes a synthetic "Disable" track at id == -1 — filter it out.
            val real = audio.filter { it.id != -1 }
            audioTrackIds = real.map { it.id }
            _audioTracks.value = real.map { it.name ?: "Track" }
            val currentId = mediaPlayer.audioTrack
            _selectedAudioTrack.value = audioTrackIds.indexOf(currentId).coerceAtLeast(0)
        } else {
            audioTrackIds = emptyList()
            _audioTracks.value = emptyList()
        }

        val spu = mediaPlayer.spuTracks
        if (spu != null) {
            val real = spu.filter { it.id != -1 }
            subtitleTrackIds = real.map { it.id }
            _availableSubtitleTracks.value = real.map { it.name ?: "Subtitle" }
            val currentId = mediaPlayer.spuTrack
            _selectedSubtitleTrack.value = subtitleTrackIds.indexOf(currentId)
        } else {
            subtitleTrackIds = emptyList()
            _availableSubtitleTracks.value = emptyList()
        }
    }

    fun toggleSubtitles(enable: Boolean) {
        _subtitlesEnabled.value = enable
        if (enable) {
            // Re-select the first available subtitle track (or last selected).
            val targetIndex = _selectedSubtitleTrack.value.takeIf { it >= 0 } ?: 0
            if (targetIndex in subtitleTrackIds.indices) {
                mediaPlayer.spuTrack = subtitleTrackIds[targetIndex]
                _selectedSubtitleTrack.value = targetIndex
            }
        } else {
            mediaPlayer.spuTrack = -1
            _selectedSubtitleTrack.value = -1
        }
    }

    fun selectAudioTrack(index: Int) {
        if (index !in audioTrackIds.indices) return
        try {
            mediaPlayer.audioTrack = audioTrackIds[index]
            _selectedAudioTrack.value = index
        } catch (_: Exception) {}
    }

    fun selectSubtitleTrack(index: Int) {
        if (index !in subtitleTrackIds.indices) return
        try {
            mediaPlayer.spuTrack = subtitleTrackIds[index]
            _subtitlesEnabled.value = true
            _selectedSubtitleTrack.value = index
        } catch (_: Exception) {
            // Even if VLC fails to render the track it will not crash the player —
            // it just falls back to no subtitles.
        }
    }

    fun setAudioDelay(delay: Float) {
        _audioDelay.value = delay
        // libVLC expects microseconds.
        mediaPlayer.audioDelay = (delay * 1_000_000L).toLong()
    }

    fun setSubtitleDelay(delay: Float) {
        _subtitleDelay.value = delay
        mediaPlayer.spuDelay = (delay * 1_000_000L).toLong()
    }

    fun setSubtitlePosition(position: String) {
        _subtitlePosition.value = position
        // VLC has --sub-margin but it's a global LibVLC option; skip dynamic for now.
    }

    fun setSubtitleSize(size: Float) { _subtitleSize.value = size }
    fun setSubtitleTextColor(color: Long) { _subtitleTextColor.value = color }
    fun setSubtitleBgAlpha(alpha: Float) { _subtitleBgAlpha.value = alpha }

    fun cancel() {
        trackRefreshJob?.cancel()
    }
}
