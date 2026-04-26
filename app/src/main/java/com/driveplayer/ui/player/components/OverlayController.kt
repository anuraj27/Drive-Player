package com.driveplayer.ui.player.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.driveplayer.ui.theme.AccentPrimary
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayController(
    fileName: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    currentPosition: Long,
    bufferedPosition: Long,
    duration: Long,
    isLandscape: Boolean,
    playbackSpeed: Float,
    isRotationLocked: Boolean,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onRotationLockToggle: () -> Unit,
    onManualRotate: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit,
    onAspectRatioClick: () -> Unit,
    onAspectRatioLongClick: () -> Unit,
    onPipClick: () -> Unit,
    sleepTimerRemaining: Int = 0,
    onSleepTimerClick: () -> Unit = {},
) {
    var sliderPosition by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // ── Top bar ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (playbackSpeed != 1f) {
                Text(
                    text = "${playbackSpeed}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            // Sleep timer button — shows remaining time badge when active
            Box(contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = onSleepTimerClick) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = "Sleep Timer",
                        tint = if (sleepTimerRemaining > 0) AccentPrimary else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                if (sleepTimerRemaining > 0) {
                    val m = sleepTimerRemaining / 60
                    val s = sleepTimerRemaining % 60
                    val label = if (m > 0) "${m}m" else "${s}s"
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AccentPrimary)
                            .padding(horizontal = 3.dp)
                    )
                }
            }
            IconButton(onClick = onPipClick) {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = onSubtitleClick) {
                Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = onAudioClick) {
                Icon(Icons.Default.Audiotrack, contentDescription = "Audio Track", tint = Color.White, modifier = Modifier.size(26.dp))
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }

        IconButton(
            onClick = onManualRotate,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 80.dp)
        ) {
            Icon(
                Icons.Default.ScreenRotation,
                contentDescription = "Manual Rotate",
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }

        // ── Center controls: [Skip -10s] [Play/Pause] [Skip +10s] ───────────
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onSeekBack,
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.White.copy(0.1f), CircleShape)
            ) {
                Icon(Icons.Default.Replay10, contentDescription = "Skip back 10s", tint = Color.White, modifier = Modifier.size(30.dp))
            }

            if (isBuffering) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentPrimary, modifier = Modifier.size(72.dp))
                    if (duration > 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${(bufferedPosition * 100 / duration).toInt()}%",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White.copy(0.2f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            IconButton(
                onClick = onSeekForward,
                modifier = Modifier
                    .size(54.dp)
                    .background(Color.White.copy(0.1f), CircleShape)
            ) {
                Icon(Icons.Default.Forward10, contentDescription = "Skip forward 10s", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            // Seekbar row with buffer indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayPosition = sliderPosition?.let { (it * duration).toLong() } ?: currentPosition

                Text(
                    text = formatTime(displayPosition),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.width(12.dp))

                // Seekbar + buffer track layered together
                Box(modifier = Modifier.weight(1f)) {
                    // Buffer indicator sits below the slider track
                    LinearProgressIndicator(
                        progress = { if (duration > 0) (bufferedPosition / duration.toFloat()).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(horizontal = 10.dp)
                            .align(Alignment.Center),
                        color = Color.White.copy(alpha = 0.4f),
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                    Slider(
                        value = sliderPosition ?: if (duration > 0) currentPosition / duration.toFloat() else 0f,
                        onValueChange = { sliderPosition = it },
                        onValueChangeFinished = {
                            sliderPosition?.let { onSeek((it * duration).toLong()) }
                            sliderPosition = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(
                            thumbColor = AccentPrimary,
                            activeTrackColor = AccentPrimary,
                            inactiveTrackColor = Color.Transparent
                        )
                    )
                }

                Spacer(Modifier.width(12.dp))

                Text(
                    text = formatTime(duration),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(4.dp))

            // Icon button row: [Lock | RotationLock] ←→ []
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row {
                    IconButton(onClick = onLock) {
                        Icon(Icons.Default.LockOpen, contentDescription = "Lock", tint = Color.White)
                    }
                    IconButton(onClick = onRotationLockToggle) {
                        val lockIcon = if (isRotationLocked) {
                            if (isLandscape) Icons.Default.ScreenLockLandscape else Icons.Default.ScreenLockPortrait
                        } else {
                            Icons.Default.Autorenew
                        }
                        Icon(
                            lockIcon,
                            contentDescription = "Toggle Rotation Lock",
                            tint = Color.White
                        )
                    }
                }
                Row {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = onAspectRatioClick,
                                onLongClick = onAspectRatioLongClick
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
