package com.driveplayer.ui.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.ui.theme.*
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoFile: DriveFile,
    siblingFiles: List<DriveFile>,
    repo: DriveRepository,
    okHttpClient: OkHttpClient,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val vm: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(context, repo, okHttpClient, videoFile, siblingFiles)
    )

    val error by vm.error.collectAsStateWithLifecycle()

    // Auto-hide controls after 3 seconds of no interaction
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(3000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { /* Player released in ViewModel.onCleared() */ }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { controlsVisible = !controlsVisible }
    ) {
        // ExoPlayer surface — use AndroidView to embed the platform view
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = vm.player
                    useController = false  // We draw our own controls below
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Error overlay
        if (error != null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ErrorOutline, null, tint = ColorError, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(error ?: "", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Controls overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerControlsOverlay(vm = vm, fileName = videoFile.name, onBack = onBack)
        }
    }
}

@UnstableApi
@Composable
private fun PlayerControlsOverlay(
    vm: PlayerViewModel,
    fileName: String,
    onBack: () -> Unit
) {
    val player = vm.player
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var duration  by remember { mutableStateOf(player.duration.coerceAtLeast(0L)) }
    var position  by remember { mutableStateOf(player.currentPosition) }
    var isBuffering by remember { mutableStateOf(false) }

    // Poll playback state every 500ms for seek bar progress
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
        }

        // Center play/pause + buffering
        Box(Modifier.align(Alignment.Center)) {
            if (isBuffering) {
                CircularProgressIndicator(color = AccentPrimary, modifier = Modifier.size(56.dp))
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

            Spacer(Modifier.height(8.dp))

            // Skip ±10 seconds row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { player.seekTo((position - 10_000L).coerceAtLeast(0L)) }) {
                    Text("-10s", color = Color.White)
                }
                Spacer(Modifier.width(24.dp))
                TextButton(onClick = { player.seekTo((position + 10_000L).coerceAtMost(duration)) }) {
                    Text("+10s", color = Color.White)
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
