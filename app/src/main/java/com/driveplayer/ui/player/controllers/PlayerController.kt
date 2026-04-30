package com.driveplayer.ui.player.controllers

import android.content.Context
import android.net.Uri
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.player.DriveAuthProxy
import com.driveplayer.player.PlaybackPositionStore
import com.driveplayer.player.WatchEntry
import com.driveplayer.player.WatchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wraps libVLC's MediaPlayer behind the same StateFlow surface the rest of the app
 * was already consuming. The previous ExoPlayer implementation hit unrecoverable
 * TextRenderer.onDisabled assertions on certain subtitle formats (PGS, complex ASS) —
 * libVLC handles those formats natively.
 *
 * The constructor no longer takes an `accessToken`. The token now lives in [AppModule]
 * and is read fresh on every Drive request via [DriveAuthProxy], so a 401-driven refresh
 * automatically propagates to the streaming side without re-creating the player.
 */
class PlayerController(
    private val context: Context,
    private val repo: DriveRepository?,
    private val scope: CoroutineScope,
    private val watchHistoryStore: WatchHistoryStore? = null,
) {
    /** Shared LibVLC instance — created once per controller. */
    private val libVlc: LibVLC = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--avcodec-skiploopfilter=0",
            // Subtitle defaults — VLC's freetype renderer is much more capable than ExoPlayer's
            "--freetype-rel-fontsize=16",
            "--freetype-outline-thickness=4",
            // NOTE: --video-filter=adjust used to live here so per-Media :contrast= /
            // :saturation= options would take effect, but enabling it globally inserts
            // the adjust filter into the output pipeline for every video. On Adreno
            // (and other GPU vouts) that produces a runaway "Too high level of
            // recursion (3)" / "Failed to create video converter" loop because the
            // chroma conversion pipeline can't satisfy the filter's input format.
            // Instead we now attach :video-filter=adjust per-Media in applyVisualOptions(),
            // and ONLY when contrast/saturation actually deviate from the defaults.
        )
    )

    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)

    private val positionStore = PlaybackPositionStore(context)
    private var currentVideoFile: DriveFile? = null
    private var currentSubtitleFile: DriveFile? = null
    private var currentLocalVideo: LocalVideo? = null
    private var currentExternalSubtitleUri: Uri? = null
    // Stable key used by the position store for the currently loaded media.
    // Cloud: the Drive file id. Local: "local_<MediaStore id>" (or LocalVideo.positionKey
    // for synthetic sources like played downloads).
    private var currentPositionKey: String? = null
    private var hasSeekedToSavedPosition = false
    // Set by restartWithCurrentOptions() so the existing seek-to-resume path picks
    // an explicit position (independent of the 5s threshold used for autosaved entries).
    @Volatile private var pendingResumeMs: Long = 0L

    @Volatile private var isReleased = false
    private var pollJob: Job? = null

    // Held open for the lifetime of the current local media — libVLC reads the fd lazily
    // on play(), so closing it eagerly produces "Bad file descriptor".
    private var currentLocalPfd: android.os.ParcelFileDescriptor? = null

    // Localhost proxy that adds the OAuth Bearer header for cloud (Drive) playback.
    private var currentProxy: DriveAuthProxy? = null
    private var currentSubProxy: DriveAuthProxy? = null

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

    // Buffer fill (0..100) reported by libVLC. Distinct from buffered POSITION
    // (the latter is an estimate of how far ahead of the playhead is buffered).
    private val _bufferingPercent = MutableStateFlow(0f)
    val bufferingPercent: StateFlow<Float> = _bufferingPercent

    private var isLoopingSegment = false
    @Volatile private var loopStartMs = 0L
    @Volatile private var loopEndMs = 0L

    // Set to true once we get our first valid length — otherwise STATE_READY-like detection
    // could fire at position 0 before metadata is available.
    @Volatile private var lengthKnown = false

    // ── Visual settings (subtitle styling + adjust-filter) ───────────────────
    // These are read on every prepare()/restart() and turned into per-Media options
    // for libVLC. Sliders update the values; calling restartWithCurrentOptions()
    // applies them to playback (libVLC 3.x can't change these mid-stream).
    @Volatile private var pendingContrast: Float = 1f
    @Volatile private var pendingSaturation: Float = 1f
    @Volatile private var pendingSubtitleScalePercent: Int = 100
    @Volatile private var pendingSubtitleColorRgb: Int = 0xFFFFFF
    @Volatile private var pendingSubtitleBgOpacity: Int = 0

    /**
     * Snapshot the user's current visual preferences. Values are applied to the next
     * Media (or the same media after [restartWithCurrentOptions]).
     *
     * @param subtitleScalePercent libVLC's sub-text-scale (10..400, 100 = default).
     */
    fun updateVisualSettings(
        contrast: Float,
        saturation: Float,
        subtitleScalePercent: Int,
        subtitleColorArgb: Long,
        subtitleBgAlpha: Float,
    ) {
        pendingContrast = contrast.coerceIn(0f, 2f)
        pendingSaturation = saturation.coerceIn(0f, 3f)
        pendingSubtitleScalePercent = subtitleScalePercent.coerceIn(10, 400)
        pendingSubtitleColorRgb = (subtitleColorArgb and 0x00FFFFFFL).toInt()
        pendingSubtitleBgOpacity = (subtitleBgAlpha.coerceIn(0f, 1f) * 255f).toInt()
    }

    private fun applyVisualOptions(media: Media) {
        // Only enable the adjust filter when the user has actually moved the
        // contrast / saturation sliders away from neutral. Always-on adjust
        // produces the "Too high level of recursion (3) / Failed to create
        // video filter 'adjust'" spam on Adreno-class GPUs because libVLC's
        // chroma converter chain can't satisfy the filter's input format,
        // and that converter loop ultimately starves the vout.
        val needsAdjust = kotlin.math.abs(pendingContrast - 1f) > 0.01f ||
                          kotlin.math.abs(pendingSaturation - 1f) > 0.01f
        if (needsAdjust) {
            media.addOption(":video-filter=adjust")
            media.addOption(":contrast=${"%.2f".format(pendingContrast)}")
            media.addOption(":saturation=${"%.2f".format(pendingSaturation)}")
        }
        media.addOption(":sub-text-scale=$pendingSubtitleScalePercent")
        media.addOption(":freetype-color=$pendingSubtitleColorRgb")
        media.addOption(":freetype-background-opacity=$pendingSubtitleBgOpacity")
        media.addOption(":freetype-background-color=0")
    }

    // libVLC's MediaPlayer allows exactly one EventListener. PlayerController owns it and
    // fans out to any number of secondary listeners (e.g. SyncController) registered here.
    private val extraListeners = CopyOnWriteArrayList<MediaPlayer.EventListener>()

    /** Register an additional listener. Safe to call from any thread. */
    fun addEventListener(listener: MediaPlayer.EventListener) {
        extraListeners.addIfAbsent(listener)
    }

    fun removeEventListener(listener: MediaPlayer.EventListener) {
        extraListeners.remove(listener)
    }

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    _isPlaying.value = true
                    _isBuffering.value = false
                }
                MediaPlayer.Event.Paused -> _isPlaying.value = false
                MediaPlayer.Event.Stopped -> _isPlaying.value = false
                MediaPlayer.Event.Buffering -> {
                    // event.buffering is the buffer fill percentage (0..100).
                    // 100 means the network buffer is full and playback is healthy.
                    _bufferingPercent.value = event.buffering
                    _isBuffering.value = event.buffering < 100f
                    // Best-effort buffered POSITION estimate: when buffer is full, assume
                    // ~network-caching ms of media is ready ahead of the playhead. libVLC
                    // doesn't expose absolute buffered bytes, so this matches the "~1.5s
                    // ahead" reality of our --network-caching=1500 setting.
                    if (event.buffering >= 100f && _duration.value > 0L) {
                        val ahead = (mediaPlayer.time + 1500L).coerceAtMost(_duration.value)
                        _bufferedPosition.value = ahead
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    _duration.value = event.lengthChanged.coerceAtLeast(0L)
                    lengthKnown = _duration.value > 0L
                    // Restore saved position once duration is known. An explicit
                    // [pendingResumeMs] (set by restartWithCurrentOptions) takes priority
                    // over the autosaved position (which has a 5s threshold).
                    if (!hasSeekedToSavedPosition && lengthKnown) {
                        hasSeekedToSavedPosition = true
                        val explicit = pendingResumeMs
                        pendingResumeMs = 0L
                        when {
                            explicit > 0L -> mediaPlayer.time = explicit.coerceAtMost(_duration.value)
                            else -> currentPositionKey?.let { key ->
                                val saved = positionStore.get(key)
                                if (saved > 5_000L) mediaPlayer.time = saved
                            }
                        }
                    }
                }
                MediaPlayer.Event.TimeChanged -> {
                    _currentPosition.value = event.timeChanged.coerceAtLeast(0L)
                }
                MediaPlayer.Event.PositionChanged -> {
                    // PositionChanged carries the playhead fraction, NOT the buffer fraction.
                    // Buffered position is updated separately under MediaPlayer.Event.Buffering.
                }
                MediaPlayer.Event.EndReached -> {
                    if (loopOne) {
                        // Single-track loop — restart, do NOT clear saved position or history.
                        mediaPlayer.time = 0L
                        mediaPlayer.play()
                    } else {
                        _isPlaying.value = false
                        currentPositionKey?.let { key -> positionStore.clear(key) }
                        currentVideoFile?.let { file ->
                            scope.launch { watchHistoryStore?.clear(file.id) }
                        }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    _error.value = "Playback error"
                }
            }

            // Fan out to secondary listeners after our own state is updated.
            for (l in extraListeners) {
                try { l.onEvent(event) } catch (_: Exception) { /* never let one listener break the chain */ }
            }
        }

        // Background loop: poll position for AB-loop detection and watch-history checkpointing.
        // VLC fires TimeChanged events too, but having an explicit cadence keeps history
        // saves predictable.
        //
        // CRITICAL: this loop must check `isReleased` at the top of every iteration
        // BEFORE touching the native MediaPlayer. The coroutine lives in viewModelScope
        // and won't actually be cancelled until the activity dies — but the native
        // VLCObject is freed earlier (in release(), e.g. on screen back-navigation).
        // Calling mediaPlayer.isPlaying on a freed native instance throws
        // "IllegalStateException: can't get VLCObject instance" and that propagates as
        // an unhandled coroutine failure → app crash.
        pollJob = scope.launch {
            var lastSaveMs = 0L
            while (isActive) {
                if (isReleased) return@launch
                val playing = try { mediaPlayer.isPlaying } catch (_: Exception) { return@launch }
                if (playing) {
                    val pos = try { mediaPlayer.time.coerceAtLeast(0L) }
                              catch (_: Exception) { return@launch }
                    if (pos - lastSaveMs >= 5_000L) {
                        // Persist resumable position for both local and cloud media.
                        currentPositionKey?.let { key -> positionStore.save(key, pos) }
                        // Watch-history (Continue Watching carousel) is cloud-only —
                        // local videos are surfaced through the LocalBrowser scan.
                        currentVideoFile?.let { file ->
                            val dur = _duration.value
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
                                            parentFolderId = file.parents?.firstOrNull(),
                                        )
                                    )
                                }
                            }
                        }
                        lastSaveMs = pos
                    }
                    if (isLoopingSegment && loopEndMs > 0 && pos >= loopEndMs) {
                        try { mediaPlayer.time = loopStartMs } catch (_: Exception) { return@launch }
                    }
                    delay(200)
                } else {
                    delay(500)
                }
            }
        }
    }

    /**
     * Drive streaming via a localhost proxy that injects the OAuth Bearer header.
     * Drive started rejecting `?access_token=` query params with HTTP 403 for media downloads,
     * and libVLC has no built-in custom-header support.
     */
    fun prepareAndPlay(videoFile: DriveFile, subtitleFile: DriveFile?) {
        if (isReleased) return
        if (repo == null) {
            _error.value = "Missing credentials"
            return
        }
        if (AppModule.currentAccessToken() == null) {
            _error.value = "Not signed in"
            return
        }
        currentVideoFile = videoFile
        currentSubtitleFile = subtitleFile
        currentLocalVideo = null
        currentExternalSubtitleUri = null
        currentPositionKey = videoFile.id
        hasSeekedToSavedPosition = false
        lengthKnown = false

        // Tear down any previous proxies before starting new ones.
        currentProxy?.stop(); currentProxy = null
        currentSubProxy?.stop(); currentSubProxy = null

        val proxy = DriveAuthProxy(
            targetUrl = repo.streamUrl(videoFile.id),
            tokenProvider = { AppModule.currentAccessToken() ?: "" },
            onRefreshNeeded = ::refreshTokenSync,
        ).also { it.start() }
        currentProxy = proxy

        val media = Media(libVlc, Uri.parse("http://127.0.0.1:${proxy.port}/")).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=1500")
        }
        applyVisualOptions(media)
        mediaPlayer.media = media
        media.release()

        if (subtitleFile != null) {
            val subProxy = DriveAuthProxy(
                targetUrl = repo.srtUrl(subtitleFile.id),
                tokenProvider = { AppModule.currentAccessToken() ?: "" },
                onRefreshNeeded = ::refreshTokenSync,
            ).also { it.start() }
            currentSubProxy = subProxy
            mediaPlayer.addSlave(
                org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                Uri.parse("http://127.0.0.1:${subProxy.port}/"),
                true
            )
        }

        mediaPlayer.play()
    }

    fun prepareAndPlayLocal(localVideo: LocalVideo) {
        if (isReleased) return
        currentVideoFile = null
        currentSubtitleFile = null
        currentLocalVideo = localVideo
        currentExternalSubtitleUri = null
        // Resume key precedence:
        //   1. explicit LocalVideo.positionKey (e.g. "download_<fileId>" for played downloads)
        //   2. real MediaStore id → "local_<id>"
        //   3. nothing — synthetic source with no stable key
        currentPositionKey = localVideo.positionKey
            ?: if (localVideo.id >= 0) "local_${localVideo.id}" else null
        hasSeekedToSavedPosition = false
        lengthKnown = false

        // Close any pfd held from a previous local playback.
        currentLocalPfd?.let { try { it.close() } catch (_: Exception) {} }
        currentLocalPfd = null

        // Prefer a real file path: libVLC's fd:// access is non-seekable for some content
        // providers (manifests as "cannot peek" / "Bad file descriptor"). A direct file
        // path always gives a seekable stream.
        val directFile = localVideo.path.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        val media: Media = if (directFile != null && directFile.canRead()) {
            Media(libVlc, directFile.absolutePath)
        } else {
            // Fallback: open via ContentResolver and hand libVLC a fd. Keep pfd alive —
            // libVLC reads it lazily during play().
            val pfd = try {
                context.contentResolver.openFileDescriptor(localVideo.uri, "r")
            } catch (e: Exception) {
                _error.value = "Cannot open file: ${e.message}"
                return
            }
            if (pfd == null) {
                _error.value = "Cannot open file"
                return
            }
            currentLocalPfd = pfd
            Media(libVlc, pfd.fileDescriptor)
        }
        media.setHWDecoderEnabled(true, false)
        applyVisualOptions(media)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    fun loadExternalSubtitle(subtitleUri: Uri) {
        if (isReleased) return
        currentExternalSubtitleUri = subtitleUri
        // libVLC accepts external subtitles at any time via addSlave.
        try {
            mediaPlayer.addSlave(
                org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                subtitleUri,
                true
            )
        } catch (_: Exception) {}
    }

    /**
     * Re-prepare the current media so the latest visual settings (contrast, saturation,
     * subtitle styling) are applied. libVLC 3.x can't update :contrast / :sub-text-scale
     * mid-stream — these are per-Media options and only take effect when a new Media
     * starts. We capture the current playhead, re-prepare from the same source, and
     * resume from the captured position via [pendingResumeMs].
     */
    fun restartWithCurrentOptions() {
        if (isReleased) return
        val pos = try { mediaPlayer.time.coerceAtLeast(0L) } catch (_: Exception) { return }
        val wasPlaying = try { mediaPlayer.isPlaying } catch (_: Exception) { return }
        pendingResumeMs = pos
        val drive = currentVideoFile
        val sub = currentSubtitleFile
        val local = currentLocalVideo
        val extSub = currentExternalSubtitleUri
        when {
            drive != null -> prepareAndPlay(drive, sub)
            local != null -> prepareAndPlayLocal(local)
            else -> {
                pendingResumeMs = 0L
                return
            }
        }
        if (drive != null && extSub != null) {
            // External subs were added on top of the cloud stream — re-attach.
            try { loadExternalSubtitle(extSub) } catch (_: Exception) {}
        }
        if (!wasPlaying) {
            // Don't auto-resume if the user paused before changing the slider.
            pause()
        }
    }

    /**
     * Synchronous helper for [DriveAuthProxy]: it runs on a non-coroutine socket thread
     * but we need the suspend [AppModule.refreshAccessToken] to complete before retrying
     * the upstream request. runBlocking is appropriate here because the proxy thread is
     * already dedicated to that single connection.
     */
    private fun refreshTokenSync(): Boolean = runBlocking {
        runCatching { AppModule.refreshAccessToken() }.isSuccess
    }

    fun play() {
        if (isReleased) return
        try { mediaPlayer.play() } catch (_: Exception) {}
    }
    fun pause() {
        if (isReleased) return
        try { mediaPlayer.pause() } catch (_: Exception) {}
    }

    fun seekTo(position: Long) {
        if (isReleased) return
        val maxDuration = _duration.value
        val target = if (maxDuration > 0L) position.coerceIn(0L, maxDuration) else position.coerceAtLeast(0L)
        try { mediaPlayer.time = target } catch (_: Exception) { return }
        _currentPosition.value = target
    }

    fun seekBy(offsetMs: Long) {
        if (isReleased) return
        val current = try { mediaPlayer.time } catch (_: Exception) { return }
        seekTo(current + offsetMs)
    }

    fun setSpeed(speed: Float) {
        if (isReleased) return
        _playbackSpeed.value = speed
        try { mediaPlayer.rate = speed } catch (_: Exception) {}
    }

    /** libVLC has no native single-track loop; loop is handled in the main listener's EndReached branch. */
    @Volatile private var loopOne = false
    fun setLooping(repeat: Boolean) {
        loopOne = repeat
        // Loop logic lives inside the single setEventListener registered in init().
        // Calling setEventListener again here would silently replace ours and kill
        // every StateFlow update — libVLC supports exactly one listener.
    }

    fun setLoopStart() {
        if (isReleased) return
        _abLoopStart.value = try { mediaPlayer.time } catch (_: Exception) { return }
        if (_abLoopEnd.value > _abLoopStart.value) activateABLoop()
    }

    fun setLoopEnd() {
        if (isReleased) return
        _abLoopEnd.value = try { mediaPlayer.time } catch (_: Exception) { return }
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
        if (isReleased) return
        _error.value = null
        try { mediaPlayer.play() } catch (_: Exception) {}
    }

    private fun activateABLoop() {
        isLoopingSegment = true
        loopStartMs = _abLoopStart.value
        loopEndMs = _abLoopEnd.value
    }

    /** Maps to ExoPlayer-era resize modes used by the UI's existing toggle:
     *  0=FIT, 3=FILL, 4=ZOOM. */
    fun setResizeMode(mode: Int) {
        if (isReleased) return
        try {
            mediaPlayer.videoScale = when (mode) {
                3 -> MediaPlayer.ScaleType.SURFACE_FILL          // fill (stretch)
                4 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN    // crop / zoom
                else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT   // letterbox fit
            }
        } catch (_: Exception) {}
    }

    fun release() {
        // Idempotent — safe to call from AndroidView.onRelease, the PlayerScreen
        // safety-net DisposableEffect, AND ViewModel.onCleared().
        // libVLC throws IllegalStateException on double release.
        if (isReleased) return
        isReleased = true
        // Stop the polling loop FIRST so it can't see a half-released native instance.
        // The loop also re-checks `isReleased` after each suspend point, so even if it
        // wakes up between the cancel and the actual native release it will bail out
        // before calling into libVLC.
        pollJob?.cancel(); pollJob = null
        try { mediaPlayer.stop() } catch (_: Exception) {}
        try { mediaPlayer.detachViews() } catch (_: Exception) {}
        try { mediaPlayer.release() } catch (_: Exception) {}
        try { libVlc.release() } catch (_: Exception) {}
        currentLocalPfd?.let { try { it.close() } catch (_: Exception) {} }
        currentLocalPfd = null
        currentProxy?.stop(); currentProxy = null
        currentSubProxy?.stop(); currentSubProxy = null
    }

    fun stop() {
        if (isReleased) return
        try { mediaPlayer.stop() } catch (_: Exception) {}
    }
}
