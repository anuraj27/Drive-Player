package com.driveplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    // Cloud params (nullable)
    videoFile: DriveFile? = null,
    siblingFiles: List<DriveFile> = emptyList(),
    repo: DriveRepository? = null,
    okHttpClient: OkHttpClient? = null,
    // Local param (nullable)
    localVideo: LocalVideo? = null,
    // Unique key per video — ensures a fresh ViewModel + ExoPlayer for every video
    playerKey: String = "default",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // key = playerKey ensures a NEW ViewModel (and ExoPlayer) for each video.
    // Without this, viewModel() returns the cached dead instance after release().
    val vm: PlayerViewModel = viewModel(
        key = playerKey,
        factory = PlayerViewModel.Factory(
            context = context,
            repo = repo,
            okHttpClient = okHttpClient,
            videoFile = videoFile,
            siblingFiles = siblingFiles,
            localVideo = localVideo
        )
    )

    // Stop playback immediately when leaving this screen.
    // ViewModel.onCleared() is too slow for in-app navigation — audio keeps playing.
    DisposableEffect(Unit) {
        onDispose {
            vm.playerController.player.stop()
            vm.playerController.player.release()
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

    val brightness      by vm.displayController.brightness.collectAsStateWithLifecycle()
    val contrast        by vm.displayController.contrast.collectAsStateWithLifecycle()
    val saturation      by vm.displayController.saturation.collectAsStateWithLifecycle()

    // ── UI state ─────────────────────────────────────────────────────────────
    var controlsVisible by remember { mutableStateOf(true) }
    var activeSettingsTab by remember { mutableStateOf<SettingsTab?>(null) }
    var resizeMode      by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLooping       by remember { mutableStateOf(false) }
    var isRotationLocked by remember { mutableStateOf(false) }

    var indicatorIcon    by remember { mutableStateOf(Icons.Default.VolumeUp) }
    var indicatorText    by remember { mutableStateOf("") }
    var indicatorVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() }
    var currentVolume by remember { mutableFloatStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()) }

    val showIndicator: (ImageVector, String) -> Unit = { icon, text ->
        indicatorIcon = icon
        indicatorText = text
        indicatorVisible = true
        coroutineScope.launch { delay(1500); indicatorVisible = false }
    }

    // Do NOT pause video when settings open, as per UX principles.
    LaunchedEffect(activeSettingsTab) {
        if (activeSettingsTab == null && !isLocked) vm.playerController.play()
    }

    // Auto-hide controls only while playing (keeps them visible when paused).
    LaunchedEffect(controlsVisible, isPlaying, isLocked, activeSettingsTab) {
        if (controlsVisible && isPlaying && !isLocked && activeSettingsTab == null) {
            delay(3_000)
            controlsVisible = false
        }
    }

    // Apply brightness from DisplayController to the window.
    // brightness == -1f means "use system default" — don't override window.
    LaunchedEffect(brightness) {
        if (brightness > 0f) {
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                screenBrightness = brightness
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                vm.playerController.pause()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                // Optionally resume here if desired, but typically we let the user manually resume
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(isLandscape) {
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }

        if (isLandscape) {
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            window?.attributes = window?.attributes?.apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            window?.attributes = window?.attributes?.apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }

        onDispose { }
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

    // Build ColorMatrix paint only when contrast / saturation change — not on every recomposition.
    val videoFilterPaint = remember(contrast, saturation) {
        android.graphics.Paint().apply {
            val matrix = android.graphics.ColorMatrix().apply {
                setSaturation(saturation)
                val scale = contrast
                val trans = (-.5f * scale + .5f) * 255f
                postConcat(android.graphics.ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, trans,
                    0f, scale, 0f, 0f, trans,
                    0f, 0f, scale, 0f, trans,
                    0f, 0f, 0f, 1f, 0f
                )))
            }
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
    }

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
                        PlayerView(ctx).apply {
                            player = vm.playerController.player
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, videoFilterPaint)
                        }
                    },
                    update = { view ->
                        view.resizeMode = resizeMode
                        // Only triggers when videoFilterPaint reference changes (i.e. contrast/saturation changed)
                        view.setLayerPaint(videoFilterPaint)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
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
                    resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onAspectRatioLongClick = { activeSettingsTab = SettingsTab.RESIZE }
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
                            showIndicator(Icons.Default.Folder, "Load subtitle not implemented")
                        },
                        subtitleDelay = subtitleDelay,
                        onSubtitleDelayChange = {
                            vm.syncController.setSubtitleDelay(it)
                            showIndicator(Icons.Default.Sync, "Subtitle Sync: ${if(it>0) "+" else ""}${String.format("%.1f", it)}s")
                        },
                        subtitlePosition = subtitlePosition,
                        onSubtitlePositionChange = { vm.syncController.setSubtitlePosition(it) }
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
                        saturation           = saturation,
                        onSaturationChange   = { vm.displayController.setSaturation(it) },
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
