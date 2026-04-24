package com.driveplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.driveplayer.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import kotlin.math.abs

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
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val vm: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(context, repo, okHttpClient, videoFile, siblingFiles)
    )

    val error by vm.error.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    
    // Settings states
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var subtitlesEnabled by remember { mutableStateOf(true) }

    // Gesture indicator states
    var indicatorIcon by remember { mutableStateOf(Icons.Default.VolumeUp) }
    var indicatorText by remember { mutableStateOf("") }
    var indicatorVisible by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Temporary values during drag
    var startVolume by remember { mutableIntStateOf(0) }
    var startBrightness by remember { mutableFloatStateOf(0f) }
    var startSeekPosition by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, showSettings) {
        if (controlsVisible && !isLocked && !showSettings) {
            delay(3000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }

        // Enable immersive fullscreen and sensor-based rotation
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        onDispose {
            // Restore everything
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    fun showIndicator(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
        indicatorIcon = icon
        indicatorText = text
        indicatorVisible = true
        coroutineScope.launch {
            delay(1000)
            indicatorVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ExoPlayer surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = vm.player
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput

                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val width = size.width
                            val player = vm.player
                            if (offset.x < width / 2) {
                                // Skip back 10s
                                player.seekTo((player.currentPosition - 10000L).coerceAtLeast(0L))
                                showIndicator(Icons.Default.Replay10, "-10s")
                            } else {
                                // Skip forward 10s
                                player.seekTo((player.currentPosition + 10000L).coerceAtMost(player.duration))
                                showIndicator(Icons.Default.Forward10, "+10s")
                            }
                        },
                        onTap = {
                            controlsVisible = !controlsVisible
                        }
                    )
                }
                .pointerInput(isLocked) {
                    if (isLocked) return@pointerInput

                    detectDragGestures(
                        onDragStart = { offset ->
                            startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            startBrightness = activity?.window?.attributes?.screenBrightness ?: 0.5f
                            if (startBrightness < 0) startBrightness = 0.5f
                            startSeekPosition = vm.player.currentPosition
                            isSeeking = false
                        },
                        onDragEnd = {
                            if (isSeeking) {
                                vm.player.seekTo(startSeekPosition)
                                isSeeking = false
                                indicatorVisible = false
                            }
                        }
                    ) { change, dragAmount ->
                        change.consume()

                        val width = size.width
                        val height = size.height

                        // Horizontal drag = Seek
                        if (abs(dragAmount.x) > abs(dragAmount.y) && (isSeeking || abs(dragAmount.x) > 5)) {
                            isSeeking = true
                            // 1 pixel = 200ms
                            val seekOffset = (dragAmount.x * 200L).toLong()
                            startSeekPosition = (startSeekPosition + seekOffset)
                                .coerceAtLeast(0L)
                                .coerceAtMost(vm.player.duration.coerceAtLeast(1L))
                            
                            showIndicator(Icons.Default.FastForward, formatMs(startSeekPosition))
                        } 
                        // Vertical drag = Volume/Brightness
                        else if (!isSeeking) {
                            val dragRatio = -(dragAmount.y / height) * 2f // Sensitivity
                            
                            if (change.position.x < width / 2) {
                                // Left side: Brightness
                                var newBrightness = startBrightness + dragRatio
                                newBrightness = newBrightness.coerceIn(0.01f, 1f)
                                activity?.window?.attributes = activity?.window?.attributes?.apply {
                                    screenBrightness = newBrightness
                                }
                                startBrightness = newBrightness
                                showIndicator(
                                    if (newBrightness > 0.5f) Icons.Default.BrightnessHigh else Icons.Default.BrightnessLow,
                                    "${(newBrightness * 100).toInt()}%"
                                )
                            } else {
                                // Right side: Volume
                                var newVolume = startVolume + (dragRatio * maxVolume).toInt()
                                newVolume = newVolume.coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                                startVolume = newVolume
                                showIndicator(
                                    if (newVolume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    "${(newVolume * 100 / maxVolume)}%"
                                )
                            }
                        }
                    }
                }
        )

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

        // Gesture Indicator Overlay
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

        // Lock button (always visible when locked)
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

        // Standard Controls overlay
        AnimatedVisibility(
            visible = controlsVisible && !isLocked && !showSettings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerControlsOverlay(
                vm = vm,
                fileName = videoFile.name,
                onSettingsClick = { showSettings = true },
                onLock = { isLocked = true; controlsVisible = false },
                onBack = onBack
            )
        }

        // Settings Modal Bottom Sheet
        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                containerColor = SurfaceVariant
            ) {
                PlayerSettingsPanel(
                    vm = vm,
                    currentResizeMode = resizeMode,
                    onResizeModeChange = { resizeMode = it },
                    currentSpeed = playbackSpeed,
                    onSpeedChange = { 
                        playbackSpeed = it
                        vm.setPlaybackSpeed(it)
                    },
                    subtitlesEnabled = subtitlesEnabled,
                    onSubtitlesToggle = { 
                        subtitlesEnabled = it
                        vm.toggleSubtitles(it)
                    }
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlayerControlsOverlay(
    vm: PlayerViewModel,
    fileName: String,
    onSettingsClick: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit
) {
    val player = vm.player
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var duration  by remember { mutableStateOf(player.duration.coerceAtLeast(0L)) }
    var position  by remember { mutableStateOf(player.currentPosition) }
    var isBuffering by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            isPlaying   = player.isPlaying
            position    = player.currentPosition
            duration    = player.duration.coerceAtLeast(0L)
            isBuffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.45f))
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                fileName,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, "Settings", tint = Color.White)
            }
        }

        // Center Actions
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lock Button
            IconButton(
                onClick = onLock,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color.Black.copy(0.3f), CircleShape)
            ) {
                Icon(Icons.Default.LockOpen, "Lock UI", tint = Color.White)
            }

            Spacer(Modifier.width(48.dp))

            // Play/Pause
            if (isBuffering) {
                CircularProgressIndicator(color = AccentPrimary, modifier = Modifier.size(72.dp))
            } else {
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White.copy(0.15f), CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            
            Spacer(Modifier.width(104.dp)) // Balance the lock button
        }

        // Bottom seek bar + time
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(formatMs(duration), color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(4.dp))

            Slider(
                value          = if (duration > 0) position / duration.toFloat() else 0f,
                onValueChange  = { player.seekTo((it * duration).toLong()) },
                modifier       = Modifier.fillMaxWidth(),
                colors         = SliderDefaults.colors(
                    thumbColor        = AccentPrimary,
                    activeTrackColor  = AccentPrimary,
                    inactiveTrackColor= Color.White.copy(0.3f)
                )
            )
        }
    }
}

@UnstableApi
@Composable
private fun PlayerSettingsPanel(
    vm: PlayerViewModel,
    currentResizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesToggle: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text("Player Settings", style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        // Playback Speed
        Text("Playback Speed: ${currentSpeed}x", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
                FilterChip(
                    selected = currentSpeed == speed,
                    onClick = { onSpeedChange(speed) },
                    label = { Text("${speed}x") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPrimary.copy(0.2f),
                        selectedLabelColor = AccentPrimary
                    )
                )
            }
        }
        
        Spacer(Modifier.height(16.dp))

        // Resize Mode
        Text("Resize Mode", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val modes = listOf(
                "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            )
            modes.forEach { (label, mode) ->
                FilterChip(
                    selected = currentResizeMode == mode,
                    onClick = { onResizeModeChange(mode) },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentPrimary.copy(0.2f),
                        selectedLabelColor = AccentPrimary
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Subtitles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Subtitles (.srt)", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = subtitlesEnabled,
                onCheckedChange = onSubtitlesToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary, checkedTrackColor = AccentPrimary.copy(0.3f))
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
