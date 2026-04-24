package com.driveplayer.ui.player.components

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

private enum class GestureType { NONE, SEEK, BRIGHTNESS, VOLUME }

@Composable
fun GestureController(
    modifier: Modifier = Modifier,
    isLocked: Boolean,
    duration: Long,
    currentPosition: Long,
    currentBrightness: Float,
    onSeek: (Long) -> Unit,
    onToggleControls: () -> Unit,
    onShowIndicator: (ImageVector, String) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onZoomChange: (Float, Float, Float) -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // rememberUpdatedState lets gesture lambdas always read the latest values
    // without recreating the pointerInput handler on every recomposition.
    val updatedPosition by rememberUpdatedState(currentPosition)
    val updatedBrightness by rememberUpdatedState(currentBrightness)

    var currentVolumeFloat by remember { mutableFloatStateOf(0f) }
    var startBrightness by remember { mutableFloatStateOf(0.5f) }
    var startSeekPosition by remember { mutableLongStateOf(0L) }
    var targetSeekPosition by remember { mutableLongStateOf(0L) }
    var startX by remember { mutableFloatStateOf(0f) }

    var activeGesture by remember { mutableStateOf(GestureType.NONE) }
    var dragAccumulatorX by remember { mutableFloatStateOf(0f) }
    var dragAccumulatorY by remember { mutableFloatStateOf(0f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 1. Pinch-to-zoom
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        panX += pan.x
                        panY += pan.y
                    } else {
                        panX = 0f
                        panY = 0f
                    }
                    onZoomChange(scale, panX, panY)
                }
            }
            // 2. Tap / double-tap
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2) {
                            onSeek((updatedPosition - 10_000L).coerceAtLeast(0L))
                            onShowIndicator(Icons.Default.Replay10, "-10s")
                        } else {
                            val target = updatedPosition + 10_000L
                            onSeek(if (duration > 0L) target.coerceAtMost(duration) else target)
                            onShowIndicator(Icons.Default.Forward10, "+10s")
                        }
                    },
                    onTap = { onToggleControls() }
                )
            }
            // 3. Swipe — seek / volume / brightness (disabled while zoomed in to allow panning)
            .pointerInput(isLocked, scale) {
                if (isLocked || scale > 1f) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        activeGesture = GestureType.NONE
                        dragAccumulatorX = 0f
                        dragAccumulatorY = 0f
                        startX = offset.x
                        currentVolumeFloat = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        startBrightness = updatedBrightness.takeIf { it > 0f } ?: 0.5f
                        startSeekPosition = updatedPosition
                        targetSeekPosition = updatedPosition
                    },
                    onDragEnd = {
                        if (activeGesture == GestureType.SEEK) {
                            onSeek(targetSeekPosition)
                        }
                        activeGesture = GestureType.NONE
                    },
                    onDragCancel = {
                        activeGesture = GestureType.NONE
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()

                    dragAccumulatorX += dragAmount.x
                    dragAccumulatorY += dragAmount.y

                    if (activeGesture == GestureType.NONE) {
                        val absX = abs(dragAccumulatorX)
                        val absY = abs(dragAccumulatorY)
                        val threshold = 30f
                        
                        if (absX > threshold || absY > threshold) {
                            if (absX > absY) { // simple majority
                                activeGesture = GestureType.SEEK
                            } else { // Vertical lock
                                if (startX < width / 2f) {
                                    activeGesture = GestureType.BRIGHTNESS
                                } else {
                                    activeGesture = GestureType.VOLUME
                                }
                            }
                        }
                    }

                    when (activeGesture) {
                        GestureType.SEEK -> {
                            val seekOffset = (dragAccumulatorX / width * 90_000L).toLong()
                            targetSeekPosition = if (duration > 0L) {
                                (startSeekPosition + seekOffset).coerceIn(0L, duration)
                            } else {
                                (startSeekPosition + seekOffset).coerceAtLeast(0L)
                            }
                            onShowIndicator(Icons.Default.FastForward, formatTime(targetSeekPosition))
                        }
                        GestureType.BRIGHTNESS -> {
                            val dragRatio = -(dragAmount.y / height) * 2f
                            val newBrightness = (startBrightness + dragRatio).coerceIn(0.01f, 1f)
                            startBrightness = newBrightness
                            onBrightnessChange(newBrightness)
                            onShowIndicator(
                                if (newBrightness > 0.5f) Icons.Default.BrightnessHigh else Icons.Default.BrightnessLow,
                                "${(newBrightness * 100).toInt()}%"
                            )
                        }
                        GestureType.VOLUME -> {
                            val dragRatio = -(dragAmount.y / height) * 2f
                            currentVolumeFloat = (currentVolumeFloat + (dragRatio * maxVolume)).coerceIn(0f, maxVolume.toFloat())
                            val newVolume = currentVolumeFloat.toInt()
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            onShowIndicator(
                                if (newVolume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                "${newVolume * 100 / maxVolume}%"
                            )
                        }
                        GestureType.NONE -> { /* Wait for threshold */ }
                    }
                }
            }
    ) {
        content()
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
