package com.driveplayer.ui.player.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

@Composable
fun GestureController(
    modifier: Modifier = Modifier,
    isLocked: Boolean,
    duration: Long,
    currentPosition: Long,
    onSeek: (Long) -> Unit,
    onToggleControls: () -> Unit,
    onShowIndicator: (ImageVector, String) -> Unit,
    onZoomChange: (Float, Float, Float) -> Unit, // scale, panX, panY
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Temporary values during drag
    var startVolume by remember { mutableIntStateOf(0) }
    var startBrightness by remember { mutableFloatStateOf(0.5f) }
    var startSeekPosition by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    
    // Zoom/Pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 1. Pinch to Zoom
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
            // 2. Double Tap / Tap
            .pointerInput(isLocked) {
                if (isLocked) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val width = size.width
                        if (offset.x < width / 2) {
                            onSeek((currentPosition - 10000L).coerceAtLeast(0L))
                            onShowIndicator(Icons.Default.Replay10, "-10s")
                        } else {
                            onSeek((currentPosition + 10000L).coerceAtMost(duration))
                            onShowIndicator(Icons.Default.Forward10, "+10s")
                        }
                    },
                    onTap = { onToggleControls() }
                )
            }
            // 3. Swipe to Seek / Volume / Brightness
            .pointerInput(isLocked, scale) { // Disable swipe gestures if zoomed in (to allow panning)
                if (isLocked || scale > 1f) return@pointerInput
                
                detectDragGestures(
                    onDragStart = { _ ->
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startBrightness = activity?.window?.attributes?.screenBrightness ?: 0.5f
                        if (startBrightness < 0) startBrightness = 0.5f
                        startSeekPosition = currentPosition
                        isSeeking = false
                    },
                    onDragEnd = {
                        if (isSeeking) {
                            onSeek(startSeekPosition)
                            isSeeking = false
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val width = size.width
                    val height = size.height

                    // Horizontal drag = Seek
                    if (abs(dragAmount.x) > abs(dragAmount.y) && (isSeeking || abs(dragAmount.x) > 5)) {
                        isSeeking = true
                        val seekOffset = (dragAmount.x * 200L).toLong()
                        startSeekPosition = (startSeekPosition + seekOffset).coerceIn(0L, duration.coerceAtLeast(1L))
                        onShowIndicator(Icons.Default.FastForward, formatTime(startSeekPosition))
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
                            onShowIndicator(
                                if (newBrightness > 0.5f) Icons.Default.BrightnessHigh else Icons.Default.BrightnessLow,
                                "${(newBrightness * 100).toInt()}%"
                            )
                        } else {
                            // Right side: Volume
                            var newVolume = startVolume + (dragRatio * maxVolume).toInt()
                            newVolume = newVolume.coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            startVolume = newVolume
                            onShowIndicator(
                                if (newVolume > 0) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                "${(newVolume * 100 / maxVolume)}%"
                            )
                        }
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
