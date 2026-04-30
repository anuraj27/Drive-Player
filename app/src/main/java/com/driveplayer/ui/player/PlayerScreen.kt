package com.driveplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.ui.player.components.GestureController
import com.driveplayer.ui.player.components.OverlayController
import com.driveplayer.ui.player.components.SettingsController
import com.driveplayer.ui.player.components.SettingsTab
import com.driveplayer.ui.player.components.AudioPanel
import com.driveplayer.ui.player.components.SubtitlePanel
import com.driveplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    // Cloud params (nullable)
    videoFile: DriveFile? = null,
    siblingFiles: List<DriveFile> = emptyList(),
    repo: DriveRepository? = null,
    accessToken: String? = null,
    // Local param (nullable)
    localVideo: LocalVideo? = null,
    // Unique key per video — ensures a fresh ViewModel + native player for every video
    playerKey: String = "default",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // key = playerKey ensures a NEW ViewModel (and libVLC instance) for each video.
    // Without this, viewModel() returns the cached dead instance after release().
    val vm: PlayerViewModel = viewModel(
        key = playerKey,
        factory = PlayerViewModel.Factory(
            context = context.applicationContext,
            repo = repo,
            accessToken = accessToken,
            videoFile = videoFile,
            siblingFiles = siblingFiles,
            localVideo = localVideo
        )
    )

    // The actual libVLC teardown happens in AndroidView.onRelease below — that hook
    // fires while VLCVideoLayout is still attached to its parent, so the TextureView's
    // Surface is alive for stop() + detachViews(). Releasing here (after the AndroidView
    // has already been removed from the tree) leaves libVLC's vout writing to a
    // destroyed Surface → native SIGSEGV when navigating back.
    //
    // This DisposableEffect is kept purely as a safety net for scenarios where
    // composition is dropped without going through the normal AndroidView lifecycle
    // (e.g. process-death recovery). release() is idempotent.
    DisposableEffect(Unit) {
        onDispose {
            vm.playerController.release()
        }
    }

    var isLocked by remember { mutableStateOf(false) }

    BackHandler {
        if (isLocked) {
            isLocked = false
        } else {
            onBack()
        }
    }

    // ── Collect all state ────────────────────────────────────────────────────
    val error           by vm.playerController.error.collectAsStateWithLifecycle()
    val isPlaying       by vm.playerController.isPlaying.collectAsStateWithLifecycle()
    val isBuffering     by vm.playerController.isBuffering.collectAsStateWithLifecycle()
    val bufferingPct    by vm.playerController.bufferingPercent.collectAsStateWithLifecycle()
    val position        by vm.playerController.currentPosition.collectAsStateWithLifecycle()
    val bufferedPos     by vm.playerController.bufferedPosition.collectAsStateWithLifecycle()
    val duration        by vm.playerController.duration.collectAsStateWithLifecycle()
    val speed           by vm.playerController.playbackSpeed.collectAsStateWithLifecycle()
    val abLoopStart     by vm.playerController.abLoopStart.collectAsStateWithLifecycle()
    val abLoopEnd       by vm.playerController.abLoopEnd.collectAsStateWithLifecycle()

    val subtitlesEnabled by vm.syncController.subtitlesEnabled.collectAsStateWithLifecycle()
    val audioTracks     by vm.syncController.audioTracks.collectAsStateWithLifecycle()
    val selectedTrack   by vm.syncController.selectedAudioTrack.collectAsStateWithLifecycle()
    val availableSubtitleTracks by vm.syncController.availableSubtitleTracks.collectAsStateWithLifecycle()
    val selectedSubtitleTrack by vm.syncController.selectedSubtitleTrack.collectAsStateWithLifecycle()
    val audioDelay by vm.syncController.audioDelay.collectAsStateWithLifecycle()
    val subtitleDelay by vm.syncController.subtitleDelay.collectAsStateWithLifecycle()
    val subtitlePosition by vm.syncController.subtitlePosition.collectAsStateWithLifecycle()
    val subtitleSize by vm.syncController.subtitleSize.collectAsStateWithLifecycle()
    val subtitleTextColor by vm.syncController.subtitleTextColor.collectAsStateWithLifecycle()
    val subtitleBgAlpha by vm.syncController.subtitleBgAlpha.collectAsStateWithLifecycle()

    val brightness      by vm.displayController.brightness.collectAsStateWithLifecycle()
    val contrast        by vm.displayController.contrast.collectAsStateWithLifecycle()
    val saturation      by vm.displayController.saturation.collectAsStateWithLifecycle()

    val subtitleFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.playerController.loadExternalSubtitle(it) } }

    // ── UI state ─────────────────────────────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var activeSettingsTab by remember { mutableStateOf<SettingsTab?>(null) }
    // 0 = FIT, 3 = FILL, 4 = ZOOM (kept as int constants to preserve the SettingsController API).
    var resizeMode      by remember { mutableIntStateOf(0) }
    var isLooping       by remember { mutableStateOf(false) }
    var isRotationLocked by remember { mutableStateOf(false) }

    // Track if video was playing before screen-off / activity pause so we can resume.
    var wasPlayingBeforePause by remember { mutableStateOf(false) }

    // Sleep timer state
    var sleepTimerRemainingSeconds by remember { mutableIntStateOf(0) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sleepTimerRemainingSeconds) {
        if (sleepTimerRemainingSeconds > 0) {
            delay(1_000)
            sleepTimerRemainingSeconds--
            if (sleepTimerRemainingSeconds == 0) vm.playerController.pause()
        }
    }

    var indicatorIcon    by remember { mutableStateOf(Icons.Default.VolumeUp) }
    var indicatorText    by remember { mutableStateOf("") }
    var indicatorVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() }
    var currentVolume by remember { mutableFloatStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()) }

    // The volume gesture in GestureController writes directly to AudioManager.
    // Re-poll the system volume whenever the AudioPanel opens so the slider isn't stale.
    LaunchedEffect(activeSettingsTab) {
        if (activeSettingsTab == SettingsTab.AUDIO) {
            currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        }
    }

    val showIndicator: (ImageVector, String) -> Unit = { icon, text ->
        indicatorIcon = icon
        indicatorText = text
        indicatorVisible = true
        coroutineScope.launch { delay(1500); indicatorVisible = false }
    }

    // Resume only if video was already playing before settings opened.
    var wasPlayingBeforeSettings by remember { mutableStateOf(false) }
    LaunchedEffect(activeSettingsTab) {
        if (activeSettingsTab != null) {
            wasPlayingBeforeSettings = isPlaying
        } else if (wasPlayingBeforeSettings && !isLocked) {
            vm.playerController.play()
        }
    }

    // Auto-hide controls only while playing (keeps them visible when paused).
    LaunchedEffect(controlsVisible, isPlaying, isLocked, activeSettingsTab) {
        if (controlsVisible && isPlaying && !isLocked && activeSettingsTab == null) {
            delay(3_000)
            controlsVisible = false
        }
    }

    // Apply brightness from DisplayController to the window.
    // brightness == -1f means "use system default" — clear the override so a
    // DisplayController.resetFilters() actually returns the screen to system brightness
    // while the player is still on screen.
    LaunchedEffect(brightness) {
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightness > 0f) brightness
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Pause whenever the activity is backgrounded (screen off, recents,
                    // home button) so we never leak audio behind the lock screen.
                    if (vm.playerController.isPlaying.value) {
                        wasPlayingBeforePause = true
                        vm.playerController.pause()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // Double-safety in case ON_PAUSE didn't catch the transition.
                    if (vm.playerController.isPlaying.value) {
                        wasPlayingBeforePause = true
                        vm.playerController.pause()
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Coming back to the foreground — TextureView preserves the GL texture
                    // across screen-off cycles, so we just resume playback. No detach/attach
                    // dance needed.
                    if (wasPlayingBeforePause) {
                        vm.playerController.play()
                        wasPlayingBeforePause = false
                    }
                }
                else -> { /* Other lifecycle events not handled */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // System bars (status + navigation) follow the player overlay: they appear
    // whenever the user taps to reveal player controls (or opens a settings panel)
    // and hide again when the controls auto-hide. Behaviour stays "swipe from
    // edge to peek" while controls are hidden, so users on full-immersion phones
    // can still summon the nav bar without tapping the video. We also extend
    // through display cutouts in landscape.
    DisposableEffect(Unit) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose { }
    }

    LaunchedEffect(controlsVisible, isLocked, activeSettingsTab) {
        val window = activity?.window ?: return@LaunchedEffect
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        // Show the system bars together with the player overlay or any open
        // settings panel; otherwise stay in immersive mode.
        val shouldShow = (controlsVisible && !isLocked) || activeSettingsTab != null
        if (shouldShow) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            val window = activity?.window
            val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    // Push current visual settings into PlayerController so it can apply them via
    // libVLC Media options on (re)start. Changes during playback take effect after
    // a slider-commit-driven restart (see SettingsController callbacks below).
    LaunchedEffect(contrast, saturation, subtitleSize, subtitleTextColor, subtitleBgAlpha) {
        // SubtitlePanel slider is in sp (10..32). 16sp == 100% in libVLC's sub-text-scale.
        val scalePercent = ((subtitleSize / 16f) * 100f).toInt()
        vm.playerController.updateVisualSettings(
            contrast = contrast,
            saturation = saturation,
            subtitleScalePercent = scalePercent,
            subtitleColorArgb = subtitleTextColor,
            subtitleBgAlpha = subtitleBgAlpha,
        )
    }

    // Push resize mode into the player whenever it changes.
    LaunchedEffect(resizeMode) { vm.playerController.setResizeMode(resizeMode) }

    var scaleState by remember { mutableFloatStateOf(1f) }
    var panXState  by remember { mutableFloatStateOf(0f) }
    var panYState  by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        GestureController(
            isLocked         = isLocked,
            duration         = duration,
            currentPosition  = position,
            currentBrightness = brightness,
            onSeek           = { vm.playerController.seekTo(it) },
            onToggleControls = { controlsVisible = !controlsVisible },
            onShowIndicator  = showIndicator,
            onBrightnessChange = { vm.displayController.setBrightness(it) },
            onZoomChange     = { s, px, py -> scaleState = s; panXState = px; panYState = py }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleState
                        scaleY = scaleState
                        translationX = panXState
                        translationY = panYState
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        org.videolan.libvlc.util.VLCVideoLayout(ctx).apply {
                            keepScreenOn = true
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            // attachViews(layout, displayManager, subtitles, useTextureView).
                            // subtitles=false — VLC's internal subtitle SurfaceView doesn't
                            // work with TextureView mode. Subtitles render onto the main surface.
                            // useTextureView=true — TextureView renders to a GL texture that
                            // persists across screen-off/on cycles, so the vout and MediaCodec
                            // decoder pipeline survive screen-off without needing a rebuild.
                            vm.playerController.mediaPlayer.attachViews(
                                this, null, false, true
                            )
                            // Kick off playback now that the video surface is attached.
                            // Doing this earlier (in ViewModel.init) starves libVLC's vout
                            // of a Surface and decoding fails permanently.
                            vm.startPlaybackOnce()
                        }
                    },
                    update = { /* surface handled by libVLC; no per-frame work */ },
                    // Release while the view is still attached to its parent — i.e. the
                    // TextureView Surface is alive for stop() and detachViews(). If we
                    // release later (in DisposableEffect.onDispose) the parent has already
                    // removed the layout, the Surface is destroyed, and libVLC's vout
                    // segfaults the process trying to render the next frame.
                    onRelease = { vm.playerController.release() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ── Sleep timer dialog ───────────────────────────────────────────────
        if (showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { showSleepTimerDialog = false },
                title = { Text("Sleep Timer") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes",
                               45 to "45 minutes", 60 to "60 minutes").forEach { (minutes, label) ->
                            val isSelected = sleepTimerRemainingSeconds == minutes * 60
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) AccentPrimary.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        sleepTimerRemainingSeconds = minutes * 60
                                        showSleepTimerDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, color = if (isSelected) AccentPrimary else Color.White)
                                if (isSelected) Icon(Icons.Default.Check, null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                },
                confirmButton = {},
                containerColor = CardSurface
            )
        }

        // ── Error overlay ────────────────────────────────────────────────────
        error?.let { errorMessage ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = ColorError, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { vm.playerController.retryPlayback() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) { Text("Retry") }
                }
            }
        }

        // ── Gesture indicator (volume / brightness / seek) ───────────────────
        AnimatedVisibility(
            visible = indicatorVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(0.6f))
                    .padding(24.dp)
            ) {
                Icon(indicatorIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text(indicatorText, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        }

        // ── Lock button (shown when locked) ──────────────────────────────────
        if (isLocked) {
            Box(Modifier.fillMaxSize()) {
                IconButton(
                    onClick = { isLocked = false },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .size(52.dp)
                        .background(Color.Black.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Lock, "Unlock", tint = Color.White)
                }
            }
        }

        // ── Playback controls ─────────────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible && !isLocked && activeSettingsTab == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            OverlayController(
                fileName        = videoFile?.name ?: localVideo?.title ?: "Unknown Video",
                isPlaying       = isPlaying,
                isBuffering     = isBuffering,
                bufferingPercent = bufferingPct,
                currentPosition = position,
                bufferedPosition = bufferedPos,
                duration        = duration,
                isLandscape     = isLandscape,
                playbackSpeed   = speed,
                isRotationLocked = isRotationLocked,
                onPlayPause     = { if (isPlaying) vm.playerController.pause() else vm.playerController.play() },
                onSeekBack      = { vm.playerController.seekBy(-10_000L) },
                onSeekForward   = { vm.playerController.seekBy(10_000L) },
                onSeek          = { vm.playerController.seekTo(it) },
                onSettingsClick = { activeSettingsTab = SettingsTab.MAIN_MENU },
                onRotationLockToggle = {
                    isRotationLocked = !isRotationLocked
                    activity?.requestedOrientation = if (isRotationLocked) {
                        ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    }
                },
                onManualRotate = {
                    isRotationLocked = true
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                onAudioClick    = { activeSettingsTab = SettingsTab.AUDIO },
                onSubtitleClick = { activeSettingsTab = SettingsTab.SUBTITLES_LOOP },
                onLock          = { isLocked = true; controlsVisible = false },
                onBack          = onBack,
                onAspectRatioClick = {
                    // 0 = FIT, 3 = FILL, 4 = ZOOM (kept as constants, matched in PlayerController.setResizeMode)
                    resizeMode = when (resizeMode) {
                        0 -> 3
                        3 -> 4
                        else -> 0
                    }
                },
                onAspectRatioLongClick = { activeSettingsTab = SettingsTab.RESIZE },
                sleepTimerRemaining = sleepTimerRemainingSeconds,
                onSleepTimerClick = { showSleepTimerDialog = true }
            )
        }

        // ── Settings Panels ─────────────────────────────────────────────────
        if (activeSettingsTab == SettingsTab.AUDIO || activeSettingsTab == SettingsTab.SUBTITLES_LOOP) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { activeSettingsTab = null },
                sheetState = sheetState,
                containerColor = Color.Transparent,
                dragHandle = null
            ) {
                if (activeSettingsTab == SettingsTab.AUDIO) {
                    AudioPanel(
                        onDismiss = { activeSettingsTab = null },
                        availableAudioTracks = audioTracks,
                        selectedAudioTrack = selectedTrack,
                        onAudioTrackChange = { index, name ->
                            vm.syncController.selectAudioTrack(index)
                            showIndicator(Icons.Default.AudioFile, "Audio: $name")
                        },
                        audioDelay = audioDelay,
                        onAudioDelayChange = { 
                            vm.syncController.setAudioDelay(it)
                            showIndicator(Icons.Default.Sync, "Audio Delay: ${if(it>0) "+" else ""}${String.format("%.1f", it)}s")
                        },
                        currentVolume = currentVolume,
                        maxVolume = maxVolume,
                        onVolumeChange = {
                            currentVolume = it
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, it.roundToInt(), 0)
                        }
                    )
                } else {
                    SubtitlePanel(
                        onDismiss = { activeSettingsTab = null },
                        subtitlesEnabled = subtitlesEnabled,
                        onSubtitlesToggle = {
                            vm.syncController.toggleSubtitles(it)
                            showIndicator(Icons.Default.Subtitles, if (it) "Subtitles Enabled" else "Subtitles Disabled")
                        },
                        availableSubtitleTracks = availableSubtitleTracks,
                        selectedSubtitleTrack = selectedSubtitleTrack,
                        onSubtitleTrackChange = { index, name ->
                            vm.syncController.selectSubtitleTrack(index)
                            showIndicator(Icons.Default.Subtitles, "Subtitle: $name")
                        },
                        onLoadExternalSubtitle = {
                            subtitleFileLauncher.launch(arrayOf("application/x-subrip", "text/plain", "*/*"))
                        },
                        subtitleDelay = subtitleDelay,
                        onSubtitleDelayChange = {
                            vm.syncController.setSubtitleDelay(it)
                            showIndicator(Icons.Default.Sync, "Subtitle Sync: ${if(it>0) "+" else ""}${String.format("%.1f", it)}s")
                        },
                        subtitlePosition = subtitlePosition,
                        onSubtitlePositionChange = { vm.syncController.setSubtitlePosition(it) },
                        subtitleSize = subtitleSize,
                        onSubtitleSizeChange = { vm.syncController.setSubtitleSize(it) },
                        onSubtitleSizeCommit = { vm.playerController.restartWithCurrentOptions() },
                        subtitleTextColor = subtitleTextColor,
                        onSubtitleTextColorChange = { vm.syncController.setSubtitleTextColor(it) },
                        onSubtitleTextColorCommit = { vm.playerController.restartWithCurrentOptions() },
                        subtitleBgAlpha = subtitleBgAlpha,
                        onSubtitleBgAlphaChange = { vm.syncController.setSubtitleBgAlpha(it) },
                        onSubtitleBgAlphaCommit = { vm.playerController.restartWithCurrentOptions() },
                    )
                }
            }
        } else if (activeSettingsTab != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { activeSettingsTab = null }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 280.dp, max = 340.dp)
                        .fillMaxWidth(0.8f)
                        .align(Alignment.CenterStart)
                        .background(Color.Black.copy(alpha = 0.85f))
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null
                        ) { /* Consume clicks */ }
                ) {
                    SettingsController(
                        activeTab            = activeSettingsTab!!,
                        onTabChange          = { activeSettingsTab = it },
                        onDismiss            = { activeSettingsTab = null },
                        currentResizeMode    = resizeMode,
                        onResizeModeChange   = { resizeMode = it },
                        currentSpeed         = speed,
                        onSpeedChange        = { vm.playerController.setSpeed(it) },
                        subtitlesEnabled     = subtitlesEnabled,
                        onSubtitlesToggle    = { vm.syncController.toggleSubtitles(it) },
                        availableAudioTracks = audioTracks,
                        selectedAudioTrack   = selectedTrack,
                        onAudioTrackChange   = { vm.syncController.selectAudioTrack(it) },
                        brightness           = brightness,
                        onBrightnessChange   = { vm.displayController.setBrightness(it) },
                        contrast             = contrast,
                        onContrastChange     = { vm.displayController.setContrast(it) },
                        onContrastCommit     = { vm.playerController.restartWithCurrentOptions() },
                        saturation           = saturation,
                        onSaturationChange   = { vm.displayController.setSaturation(it) },
                        onSaturationCommit   = { vm.playerController.restartWithCurrentOptions() },
                        isLooping            = isLooping,
                        onLoopingToggle      = { isLooping = it; vm.playerController.setLooping(it) },
                        abLoopStart          = abLoopStart,
                        abLoopEnd            = abLoopEnd,
                        onSetLoopStart       = { vm.playerController.setLoopStart() },
                        onSetLoopEnd         = { vm.playerController.setLoopEnd() },
                        onClearABLoop        = { vm.playerController.clearABLoop() }
                    )
                }
            }
        }
    }
}
