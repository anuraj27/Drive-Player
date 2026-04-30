package com.driveplayer.ui.player.controllers

import android.content.Context
import android.net.Uri
import com.driveplayer.data.SettingsStore
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.di.AppModule
import com.driveplayer.player.DriveAuthProxy
import com.driveplayer.player.PlaybackPositionStore
import com.driveplayer.player.WatchEntry
import com.driveplayer.player.WatchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
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
    private val playbackStateStore = AppModule.playbackStateStore
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

    /** Loaded once per prepare in [prepareAndPlay] / [prepareAndPlayLocal] from the
     *  per-file [PlaybackStateStore]. Applied at `LengthChanged` so it lands AFTER
     *  the position seek and tracks have been enumerated. Nulled out after apply
     *  to prevent a re-restore on a media restart (e.g. visual-settings commit). */
    @Volatile private var pendingRestoreState: com.driveplayer.player.PlaybackState? = null

    /** Set by [applyRestoredState] so [applySubtitleDefault] knows the user's
     *  saved subtitle choice (which may be -1, i.e. "explicitly off") wins over
     *  both the file's `default` flag and the global "Subtitles enabled by
     *  default" toggle. Reset on every prepare. */
    @Volatile private var hasRestoredSubtitleChoice: Boolean = false

    /**
     * ViewModel hook for the *non*-mediaplayer side of state restoration: audio
     * and subtitle delays live in [SyncController]'s StateFlows so the in-player
     * sliders can observe them. PlayerController writes to libVLC directly but
     * has no SyncController reference, so we fan-out via this callback.
     */
    var onStateRestored: ((com.driveplayer.player.PlaybackState) -> Unit)? = null

    @Volatile private var isReleased = false
    private var pollJob: Job? = null

    /**
     * One-shot snapshot of user settings captured when the controller is built.
     * Used to seed: default playback speed, network buffer, default subtitle
     * styling, and the resume-from-last-position toggle. Settings only take
     * effect on the next prepare — toggling them while a video is on screen
     * does NOT retroactively change behaviour, which matches how VLC's
     * "default" settings work.
     *
     * Made public so [PlayerViewModel] can re-use the same snapshot to seed
     * [SyncController] sliders (avoids two separate DataStore reads racing).
     */
    val settingsSnapshot: SettingsStore.Snapshot =
        runBlocking { AppModule.settingsStore.snapshot() }

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
    // Seeded from user settings — overridden at runtime via updateVisualSettings()
    // when the user moves the in-player sliders.
    @Volatile private var pendingSubtitleScalePercent: Int = settingsSnapshot.defaultSubtitleScale
    @Volatile private var pendingSubtitleColorRgb: Int = settingsSnapshot.defaultSubtitleColor
    @Volatile private var pendingSubtitleBgOpacity: Int = settingsSnapshot.defaultSubtitleBgAlpha

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
                    // Subtitle tracks aren't enumerated until the media starts,
                    // so disabling-by-default has to happen here, not at prepare.
                    applySubtitleDefault()
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
                    // over the autosaved position (which has a 5s threshold). The
                    // autosaved seek is also gated by the user's "Resume playback"
                    // setting — explicit resumes from restart are always honoured.
                    if (!hasSeekedToSavedPosition && lengthKnown) {
                        hasSeekedToSavedPosition = true
                        val explicit = pendingResumeMs
                        pendingResumeMs = 0L
                        when {
                            explicit > 0L -> mediaPlayer.time = explicit.coerceAtMost(_duration.value)
                            settingsSnapshot.resumePlayback -> currentPositionKey?.let { key ->
                                val saved = positionStore.get(key)
                                if (saved > 5_000L) mediaPlayer.time = saved
                            }
                            else -> { /* user opted out of auto-resume */ }
                        }

                        // Restore the rest of the per-file state (track choices,
                        // delays, rate). Gated by the same resumePlayback toggle —
                        // a user who turned that off doesn't expect any per-file
                        // state to come back either.
                        if (settingsSnapshot.resumePlayback) {
                            pendingRestoreState?.let { state ->
                                applyRestoredState(state)
                                onStateRestored?.invoke(state)
                            }
                        }
                        pendingRestoreState = null
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
                        currentPositionKey?.let { key ->
                            positionStore.clear(key)
                            // The video has played to completion — drop the
                            // per-file state too, otherwise the next playback
                            // would try to restore tracks/delays from a stale
                            // record. Watch-history clear (below) follows the
                            // same finished-the-video logic.
                            scope.launch { playbackStateStore.clear(key) }
                        }
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
                        currentPositionKey?.let { key ->
                            positionStore.save(key, pos)
                            // Plus the richer per-file player state (audio/sub
                            // tracks, delays, rate, …). DataStore writes are
                            // suspending so we hop scopes — the position write
                            // above stays synchronous on SharedPrefs.
                            capturePlaybackState()?.let { state ->
                                scope.launch { playbackStateStore.save(key, state) }
                            }
                        }
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
        pendingRestoreState = runBlocking { playbackStateStore.get(videoFile.id) }
        hasRestoredSubtitleChoice = false

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
            setHWDecoderEnabled(settingsSnapshot.hardwareAcceleration == "AUTO", false)
            addOption(":network-caching=${settingsSnapshot.networkCacheMs}")
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
        applyDefaultSpeed()
        applyVolumeBoost()
        applyEqualizer()
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
        pendingRestoreState = currentPositionKey?.let { runBlocking { playbackStateStore.get(it) } }
        hasRestoredSubtitleChoice = false

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
        media.setHWDecoderEnabled(settingsSnapshot.hardwareAcceleration == "AUTO", false)
        applyVisualOptions(media)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        applyDefaultSpeed()
        applyVolumeBoost()
        applyEqualizer()
    }

    /**
     * Snap the player rate to the user's "default playback speed" setting at
     * the start of every prepare. This is a no-op for the default 1.0× value;
     * for a user-configured 1.25× / 1.5× it makes the speed sticky across
     * playback sessions without us having to persist it per-video.
     *
     * Mid-playback rate changes (the 1×/1.25×/1.5× chip in PlayerScreen) just
     * call `mediaPlayer.rate = …` directly and are session-scoped.
     */
    private fun applyDefaultSpeed() {
        val rate = settingsSnapshot.defaultPlaybackSpeed
        if (kotlin.math.abs(rate - 1f) < 0.001f) return
        try {
            mediaPlayer.rate = rate
            _playbackSpeed.value = rate
        } catch (_: Exception) {}
    }

    /**
     * Push the user's volume-boost preference to libVLC. libVLC's `volume`
     * accepts 0..200 — anything above 100 amplifies in software. We only
     * touch the volume when the boost is non-trivial (>2 % above 100); the
     * default 1.0 path leaves the OS / AudioManager fully in charge so the
     * physical volume rocker still feels right.
     */
    private fun applyVolumeBoost() {
        val pct = (settingsSnapshot.volumeBoost * 100f).toInt().coerceIn(0, 200)
        if (pct <= 102) return
        try {
            mediaPlayer.volume = pct
        } catch (_: Exception) {}
    }

    /**
     * Apply the user-selected libVLC equalizer preset. Off by default; turning
     * it on installs the chosen preset on every new prepare. Wrapped in a try
     * because libVLC throws on some odd device configs (notably Tegra-based
     * tablets) — we'd rather skip EQ than crash the player there.
     */
    private fun applyEqualizer() {
        if (!settingsSnapshot.equalizerEnabled) return
        val idx = settingsSnapshot.equalizerPreset
        if (idx < 0) return
        try {
            val eq = org.videolan.libvlc.MediaPlayer.Equalizer.createFromPreset(idx)
            mediaPlayer.setEqualizer(eq)
        } catch (_: Exception) {}
    }

    /**
     * Reconcile the subtitle-track-on-`Playing` state with the user's "Subtitles
     * enabled by default" preference and the file's own metadata.
     *
     * libVLC's auto-selection rules: pick the track flagged `default=1` (MKV);
     * otherwise pick the track matching `--sub-language=…`; otherwise leave
     * subs OFF (`spuTrack = -1`). That third branch fires for many community
     * rips that don't bother setting the default flag — the user has subs
     * "enabled by default" but sees nothing.
     *
     * Behaviour:
     *  - Toggle OFF → force `spuTrack = -1`, overriding any default the file
     *    chose. User can still flip subs on from the Subtitle panel.
     *  - Toggle ON  → keep libVLC's pick if it found one; otherwise fall back
     *    to the first non-`-1` track we can see (`-1` is the "Disable" entry
     *    libVLC always inserts at index 0 of `spuTracks`).
     *
     * Restored playback state takes precedence over both branches — see
     * [applyRestoredState] which runs immediately before this in the
     * `LengthChanged` flow.
     */
    private fun applySubtitleDefault() {
        // Restored choice always wins — that's the user's most recent decision
        // for *this* file, including "subs explicitly off" via spuTrack = -1.
        if (hasRestoredSubtitleChoice) return

        if (!settingsSnapshot.subtitlesEnabledByDefault) {
            try { mediaPlayer.spuTrack = -1 } catch (_: Exception) {}
            return
        }
        try {
            if (mediaPlayer.spuTrack == -1) {
                val tracks = mediaPlayer.spuTracks ?: return
                val firstReal = tracks.firstOrNull { it.id != -1 } ?: return
                mediaPlayer.spuTrack = firstReal.id
            }
        } catch (_: Exception) {}
    }

    /**
     * Push the per-file state captured on a previous session back onto the
     * native MediaPlayer. Tracks must be applied AFTER `LengthChanged` because
     * libVLC enumerates them as part of media parse — earlier and the IDs
     * don't exist yet. Delays and rate are pushed back into [SyncController]'s
     * StateFlows via [onStateRestored] so the in-player sliders match.
     *
     * libVLC ignores `audioTrack`/`spuTrack` writes for unknown IDs (no
     * exception), so a state from an older release of the same file with
     * different track ordering simply falls back to the file's defaults.
     */
    private fun applyRestoredState(state: com.driveplayer.player.PlaybackState) {
        try {
            // -2 sentinel = no preference recorded (e.g. brand-new entry); only
            // overwrite the player's current pick when we have a real saved id.
            if (state.audioTrackId >= 0) {
                mediaPlayer.audioTrack = state.audioTrackId
            }
            // Subtitle: -1 means "user explicitly disabled". Honour that as a
            // valid restored choice (not the same as -2 = "no record").
            if (state.subtitleTrackId == -1 || state.subtitleTrackId >= 0) {
                mediaPlayer.spuTrack = state.subtitleTrackId
                hasRestoredSubtitleChoice = true
            }
            mediaPlayer.audioDelay = state.audioDelayUs
            mediaPlayer.spuDelay  = state.subtitleDelayUs
            // Rate goes through the existing setter so the StateFlow + UI chip
            // stays consistent.
            if (kotlin.math.abs(state.playbackRate - 1f) > 0.001f) {
                setSpeed(state.playbackRate)
            }
            // Re-attach a user-attached external SRT, if any. content:// URIs
            // require a persisted permission grant to survive — we only call
            // through; addSlave silently fails for revoked URIs.
            state.externalSubtitleUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
                try {
                    val uri = Uri.parse(uriStr)
                    mediaPlayer.addSlave(
                        org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                        uri,
                        true,
                    )
                    currentExternalSubtitleUri = uri
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Snapshot of every persisted player setting at the moment of the call.
     * Returns `null` for invalid native state (released, not yet playing) so
     * the poll loop can skip the write instead of saving a row of zeros.
     */
    private fun capturePlaybackState(): com.driveplayer.player.PlaybackState? {
        if (isReleased) return null
        return try {
            com.driveplayer.player.PlaybackState(
                audioTrackId        = mediaPlayer.audioTrack,
                subtitleTrackId     = mediaPlayer.spuTrack,
                externalSubtitleUri = currentExternalSubtitleUri?.toString(),
                subtitleDelayUs     = mediaPlayer.spuDelay,
                audioDelayUs        = mediaPlayer.audioDelay,
                playbackRate        = mediaPlayer.rate,
            )
        } catch (_: Exception) {
            null
        }
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

    /** libVLC has no native single-track loop; loop is handled in the main listener's EndReached branch.
     *  Initial value comes from [SettingsStore.repeatOne] so a user who turned
     *  on "Repeat one" in Settings doesn't have to flip the in-player toggle
     *  on every video. */
    @Volatile private var loopOne = settingsSnapshot.repeatOne
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

    @OptIn(DelicateCoroutinesApi::class)
    fun release() {
        // Idempotent — safe to call from AndroidView.onRelease, the PlayerScreen
        // safety-net DisposableEffect, AND ViewModel.onCleared().
        // libVLC throws IllegalStateException on double release.
        if (isReleased) return
        // Final state checkpoint BEFORE we tear down the native player —
        // captures the latest track / delay choices in case the user exits
        // between two poll-loop ticks. We can't suspend here so we fire the
        // write off the GlobalScope; safe because PlaybackStateStore is a
        // simple DataStore with no other dependency on the controller.
        val finalKey = currentPositionKey
        val finalState = if (finalKey != null) capturePlaybackState() else null
        if (finalKey != null && finalState != null) {
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                runCatching { playbackStateStore.save(finalKey, finalState) }
            }
        }

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
