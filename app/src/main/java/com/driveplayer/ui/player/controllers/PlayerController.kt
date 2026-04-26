package com.driveplayer.ui.player.controllers

import android.content.Context
import android.net.Uri
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.player.PlaybackPositionStore
import com.driveplayer.player.WatchEntry
import com.driveplayer.player.WatchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Wraps libVLC's MediaPlayer behind the same StateFlow surface the rest of the app
 * was already consuming. The previous ExoPlayer implementation hit unrecoverable
 * TextRenderer.onDisabled assertions on certain subtitle formats (PGS, complex ASS) —
 * libVLC handles those formats natively.
 */
class PlayerController(
    private val context: Context,
    private val accessToken: String?,
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
            // Enable adjust filter so brightness/contrast/saturation can be set later
            "--video-filter=adjust",
        )
    )

    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)

    private val positionStore = PlaybackPositionStore(context)
    private var currentVideoFile: DriveFile? = null
    private var hasSeekedToSavedPosition = false

    // Held open for the lifetime of the current local media — libVLC reads the fd lazily
    // on play(), so closing it eagerly produces "Bad file descriptor".
    private var currentLocalPfd: android.os.ParcelFileDescriptor? = null

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

    // Set to true once we get our first valid length — otherwise STATE_READY-like detection
    // could fire at position 0 before metadata is available.
    @Volatile private var lengthKnown = false

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
                    // event.buffering is the buffer percentage (0..100). 100 means fully buffered.
                    _isBuffering.value = event.buffering < 100f
                }
                MediaPlayer.Event.LengthChanged -> {
                    _duration.value = event.lengthChanged.coerceAtLeast(0L)
                    lengthKnown = _duration.value > 0L
                    // Restore saved position once duration is known.
                    if (!hasSeekedToSavedPosition && lengthKnown) {
                        hasSeekedToSavedPosition = true
                        currentVideoFile?.id?.let { id ->
                            val saved = positionStore.get(id)
                            if (saved > 5_000L) mediaPlayer.time = saved
                        }
                    }
                }
                MediaPlayer.Event.TimeChanged -> {
                    _currentPosition.value = event.timeChanged.coerceAtLeast(0L)
                }
                MediaPlayer.Event.PositionChanged -> {
                    if (lengthKnown) {
                        _bufferedPosition.value =
                            (event.positionChanged * _duration.value).toLong().coerceAtLeast(0L)
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    _isPlaying.value = false
                    currentVideoFile?.let { file ->
                        positionStore.clear(file.id)
                        scope.launch { watchHistoryStore?.clear(file.id) }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    _error.value = "Playback error"
                }
            }
        }

        // Background loop: poll position for AB-loop detection and watch-history checkpointing.
        // VLC fires TimeChanged events too, but having an explicit cadence keeps history
        // saves predictable.
        scope.launch {
            var lastSaveMs = 0L
            while (isActive) {
                if (mediaPlayer.isPlaying) {
                    val pos = mediaPlayer.time.coerceAtLeast(0L)
                    if (pos - lastSaveMs >= 5_000L) {
                        currentVideoFile?.let { file ->
                            positionStore.save(file.id, pos)
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
                                        )
                                    )
                                }
                            }
                        }
                        lastSaveMs = pos
                    }
                    if (isLoopingSegment && loopEndMs > 0 && pos >= loopEndMs) {
                        mediaPlayer.time = loopStartMs
                    }
                    delay(200)
                } else {
                    delay(500)
                }
            }
        }
    }

    /** Drive streaming with `?access_token=...` — Drive accepts OAuth tokens as a query param. */
    fun prepareAndPlay(videoFile: DriveFile, subtitleFile: DriveFile?) {
        if (repo == null || accessToken == null) {
            _error.value = "Missing credentials"
            return
        }
        currentVideoFile = videoFile
        hasSeekedToSavedPosition = false
        lengthKnown = false

        val url = "${repo.streamUrl(videoFile.id)}&access_token=$accessToken"
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
            addOption(":network-caching=1500")
        }
        mediaPlayer.media = media
        media.release()

        if (subtitleFile != null) {
            val subUrl = "${repo.srtUrl(subtitleFile.id)}&access_token=$accessToken"
            mediaPlayer.addSlave(
                org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
                Uri.parse(subUrl),
                true
            )
        }

        mediaPlayer.play()
    }

    fun prepareAndPlayLocal(localVideo: LocalVideo) {
        currentVideoFile = null
        hasSeekedToSavedPosition = false
        lengthKnown = false

        // Close any pfd held from a previous local playback.
        currentLocalPfd?.let { try { it.close() } catch (_: Exception) {} }
        currentLocalPfd = null

        // libVLC cannot open content:// URIs directly; open via ContentResolver and hand it a fd.
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
        val media = Media(libVlc, pfd.fileDescriptor).apply {
            setHWDecoderEnabled(true, false)
        }
        mediaPlayer.media = media
        media.release()
        // Don't close pfd here — libVLC reads it lazily during play(). It's closed on next
        // prepareAndPlayLocal/release().
        mediaPlayer.play()
    }

    fun loadExternalSubtitle(subtitleUri: Uri) {
        // libVLC accepts external subtitles at any time via addSlave.
        mediaPlayer.addSlave(
            org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
            subtitleUri,
            true
        )
    }

    fun play() { mediaPlayer.play() }
    fun pause() { mediaPlayer.pause() }

    fun seekTo(position: Long) {
        val maxDuration = _duration.value
        val target = if (maxDuration > 0L) position.coerceIn(0L, maxDuration) else position.coerceAtLeast(0L)
        mediaPlayer.time = target
        _currentPosition.value = target
    }

    fun seekBy(offsetMs: Long) = seekTo(mediaPlayer.time + offsetMs)

    fun setSpeed(speed: Float) {
        _playbackSpeed.value = speed
        mediaPlayer.rate = speed
    }

    /** libVLC has no native single-track loop; we re-issue play() on EndReached if enabled. */
    @Volatile private var loopOne = false
    fun setLooping(repeat: Boolean) {
        loopOne = repeat
        // Hook EndReached for repeat — handled via a side observer.
        // (Done lazily here so we don't add the listener twice.)
        if (repeat && !loopListenerAttached) {
            loopListenerAttached = true
            mediaPlayer.setEventListener { event ->
                if (event.type == MediaPlayer.Event.EndReached && loopOne) {
                    mediaPlayer.time = 0L
                    mediaPlayer.play()
                }
            }
        }
    }
    @Volatile private var loopListenerAttached = false

    fun setLoopStart() {
        _abLoopStart.value = mediaPlayer.time
        if (_abLoopEnd.value > _abLoopStart.value) activateABLoop()
    }

    fun setLoopEnd() {
        _abLoopEnd.value = mediaPlayer.time
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
        mediaPlayer.play()
    }

    private fun activateABLoop() {
        isLoopingSegment = true
        loopStartMs = _abLoopStart.value
        loopEndMs = _abLoopEnd.value
    }

    /** Maps to ExoPlayer-era resize modes used by the UI's existing toggle:
     *  0=FIT, 3=FILL, 4=ZOOM. */
    fun setResizeMode(mode: Int) {
        mediaPlayer.videoScale = when (mode) {
            3 -> MediaPlayer.ScaleType.SURFACE_FILL          // fill (stretch)
            4 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN    // crop / zoom
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT   // letterbox fit
        }
    }

    fun release() {
        try { mediaPlayer.stop() } catch (_: Exception) {}
        mediaPlayer.detachViews()
        mediaPlayer.release()
        libVlc.release()
        currentLocalPfd?.let { try { it.close() } catch (_: Exception) {} }
        currentLocalPfd = null
    }

    fun stop() { mediaPlayer.stop() }
}
