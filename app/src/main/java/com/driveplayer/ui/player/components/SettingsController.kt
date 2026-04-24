package com.driveplayer.ui.player.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout
import com.driveplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsController(
    currentResizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesToggle: (Boolean) -> Unit,
    subtitleDelay: Long,
    onSubtitleDelayChange: (Long) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    contrast: Float,
    onContrastChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    isLooping: Boolean,
    onLoopingToggle: (Boolean) -> Unit,
    abLoopStart: Long,
    abLoopEnd: Long,
    onSetLoopStart: () -> Unit,
    onSetLoopEnd: () -> Unit,
    onClearABLoop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
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

        // Subtitles & Loop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Subtitles", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = subtitlesEnabled, onCheckedChange = onSubtitlesToggle)
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Loop Video", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Switch(checked = isLooping, onCheckedChange = onLoopingToggle)
        }

        Spacer(Modifier.height(16.dp))
        
        Text("A-B Loop", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSetLoopStart, colors = ButtonDefaults.buttonColors(containerColor = if (abLoopStart > 0) AccentPrimary else SurfaceVariant)) { Text("Set A") }
            Button(onClick = onSetLoopEnd, colors = ButtonDefaults.buttonColors(containerColor = if (abLoopEnd > 0) AccentPrimary else SurfaceVariant)) { Text("Set B") }
            OutlinedButton(onClick = onClearABLoop) { Text("Clear") }
        }

        Spacer(Modifier.height(16.dp))
        
        // Sync Controls
        Text("Subtitle Delay (ms): $subtitleDelay", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = subtitleDelay.toFloat(),
            onValueChange = { onSubtitleDelayChange(it.toLong()) },
            valueRange = -5000f..5000f
        )

        Spacer(Modifier.height(16.dp))

        // Video Filters
        Text("Video Filters (Simulated)", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        
        Text("Brightness: ${"%.2f".format(brightness)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Slider(value = brightness, onValueChange = onBrightnessChange, valueRange = 0f..2f)
        
        Text("Contrast: ${"%.2f".format(contrast)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Slider(value = contrast, onValueChange = onContrastChange, valueRange = 0f..2f)
        
        Text("Saturation: ${"%.2f".format(saturation)}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        Slider(value = saturation, onValueChange = onSaturationChange, valueRange = 0f..2f)

        Spacer(Modifier.height(32.dp))
    }
}
