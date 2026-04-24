package com.driveplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.ui.player.components.GestureController
import com.driveplayer.ui.player.components.OverlayController
import com.driveplayer.ui.player.components.SettingsController
import com.driveplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    videoFile: DriveFile,
    siblingFiles: List<DriveFile>,
    repo: DriveRepository,
    okHttpClient: OkHttpClient,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val vm: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(context, repo, okHttpClient, videoFile, siblingFiles)
    )

    val error by vm.playerController.error.collectAsStateWithLifecycle()
    val isPlaying by vm.playerController.isPlaying.collectAsStateWithLifecycle()
    val isBuffering by vm.playerController.isBuffering.collectAsStateWithLifecycle()
    val position by vm.playerController.currentPosition.collectAsStateWithLifecycle()
    val duration by vm.playerController.duration.collectAsStateWithLifecycle()
    val speed by vm.playerController.playbackSpeed.collectAsStateWithLifecycle()
    
    val subtitlesEnabled by vm.syncController.subtitlesEnabled.collectAsStateWithLifecycle()
    val subtitleDelay by vm.syncController.subtitleDelayMs.collectAsStateWithLifecycle()

    val brightness by vm.displayController.brightness.collectAsStateWithLifecycle()
    val contrast by vm.displayController.contrast.collectAsStateWithLifecycle()
    val saturation by vm.displayController.saturation.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLooping by remember { mutableStateOf(false) }
    var abLoopStart by remember { mutableLongStateOf(0L) }
    var abLoopEnd by remember { mutableLongStateOf(0L) }

    // Gesture indicator
    var indicatorIcon by remember { mutableStateOf(Icons.Default.VolumeUp) }
    var indicatorText by remember { mutableStateOf("") }
    var indicatorVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(controlsVisible, showSettings) {
        if (controlsVisible && !isLocked && !showSettings) {
            delay(3000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }

        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    fun showIndicator(icon: ImageVector, text: String) {
        indicatorIcon = icon
        indicatorText = text
        indicatorVisible = true
        coroutineScope.launch {
            delay(1000)
            indicatorVisible = false
        }
    }

    // Video Filters (Simulated with ColorMatrix on Compose layer)
    // Compose ColorMatrix for Contrast and Saturation
    val colorMatrix = remember(contrast, saturation) {
        val cm = ColorMatrix()
        cm.setToSaturation(saturation)
        
        // Manual Contrast Application
        val scale = contrast
        val translate = (-.5f * scale + .5f) * 255f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        
        // Multiply matrices
        // Since Compose's ColorMatrix doesn't have postConcat, we just apply contrast then saturation.
        // For simple UI, this is efficient and works perfectly.
        cm
    }

    // Inner Box for Zooming
    var scaleState by remember { mutableFloatStateOf(1f) }
    var panXState by remember { mutableFloatStateOf(0f) }
    var panYState by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        GestureController(
            isLocked = isLocked,
            duration = duration,
            currentPosition = position,
            onSeek = { vm.playerController.seekTo(it) },
            onToggleControls = { controlsVisible = !controlsVisible },
            onShowIndicator = ::showIndicator,
            onZoomChange = { scale, pX, pY -> 
                scaleState = scale
                panXState = pX
                panYState = pY
            }
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
                // ExoPlayer surface
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = vm.playerController.player
                            useController = false
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            // We set LayerType to Hardware to allow ColorMatrix to affect it if possible
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply {
                                val androidMatrix = android.graphics.ColorMatrix()
                                androidMatrix.setSaturation(saturation)
                                
                                val cScale = contrast
                                val cTranslate = (-.5f * cScale + .5f) * 255f
                                val cMatrix = android.graphics.ColorMatrix(floatArrayOf(
                                    cScale, 0f, 0f, 0f, cTranslate,
                                    0f, cScale, 0f, 0f, cTranslate,
                                    0f, 0f, cScale, 0f, cTranslate,
                                    0f, 0f, 0f, 1f, 0f
                                ))
                                androidMatrix.postConcat(cMatrix)
                                colorFilter = android.graphics.ColorMatrixColorFilter(androidMatrix)
                            })
                        }
                    },
                    update = { view ->
                        view.resizeMode = resizeMode
                        
                        // Update paint on changes
                        val androidMatrix = android.graphics.ColorMatrix()
                        androidMatrix.setSaturation(saturation)
                        val cScale = contrast
                        val cTranslate = (-.5f * cScale + .5f) * 255f
                        val cMatrix = android.graphics.ColorMatrix(floatArrayOf(
                            cScale, 0f, 0f, 0f, cTranslate,
                            0f, cScale, 0f, 0f, cTranslate,
                            0f, 0f, cScale, 0f, cTranslate,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        androidMatrix.postConcat(cMatrix)
                        (view.layerType == android.view.View.LAYER_TYPE_HARDWARE) // Ensure it's HW
                        view.setLayerPaint(android.graphics.Paint().apply { 
                            colorFilter = android.graphics.ColorMatrixColorFilter(androidMatrix) 
                        })
                        view.invalidate()
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Brightness Overlay (0f to 2f)
                if (brightness < 1f) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - brightness)))
                } else if (brightness > 1f) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = (brightness - 1f) * 0.5f)))
                }
            }
        }

        // Error overlay
        error?.let { errorMessage ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = ColorError, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(errorMessage, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Gesture Indicator
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
                Icon(indicatorIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(indicatorText, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        }

        // Lock button
        if (isLocked) {
            Box(Modifier.fillMaxSize()) {
                IconButton(
                    onClick = { isLocked = false },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 32.dp)
                        .size(56.dp)
                        .background(Color.Black.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Lock, "Unlock", tint = Color.White)
                }
            }
        }

        // Standard Controls
        AnimatedVisibility(
            visible = controlsVisible && !isLocked && !showSettings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            OverlayController(
                fileName = videoFile.name,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                currentPosition = position,
                duration = duration,
                onPlayPause = { if (isPlaying) vm.playerController.pause() else vm.playerController.play() },
                onSeek = { vm.playerController.seekTo(it) },
                onSettingsClick = { showSettings = true },
                onLock = { isLocked = true; controlsVisible = false },
                onBack = onBack
            )
        }

        // Settings Modal
        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                containerColor = SurfaceVariant
            ) {
                SettingsController(
                    currentResizeMode = resizeMode,
                    onResizeModeChange = { resizeMode = it },
                    currentSpeed = speed,
                    onSpeedChange = { vm.playerController.setSpeed(it) },
                    subtitlesEnabled = subtitlesEnabled,
                    onSubtitlesToggle = { vm.syncController.toggleSubtitles(it) },
                    subtitleDelay = subtitleDelay,
                    onSubtitleDelayChange = { vm.syncController.setSubtitleDelay(it) },
                    brightness = brightness,
                    onBrightnessChange = { vm.displayController.setBrightness(it) },
                    contrast = contrast,
                    onContrastChange = { vm.displayController.setContrast(it) },
                    saturation = saturation,
                    onSaturationChange = { vm.displayController.setSaturation(it) },
                    isLooping = isLooping,
                    onLoopingToggle = { 
                        isLooping = it
                        vm.playerController.setLooping(it)
                    },
                    abLoopStart = abLoopStart,
                    abLoopEnd = abLoopEnd,
                    onSetLoopStart = { abLoopStart = position; if (abLoopEnd > 0) vm.playerController.setABLoop(abLoopStart, abLoopEnd) },
                    onSetLoopEnd = { abLoopEnd = position; if (abLoopStart > 0) vm.playerController.setABLoop(abLoopStart, abLoopEnd) },
                    onClearABLoop = { abLoopStart = 0L; abLoopEnd = 0L; vm.playerController.clearABLoop() }
                )
            }
        }
    }
}
