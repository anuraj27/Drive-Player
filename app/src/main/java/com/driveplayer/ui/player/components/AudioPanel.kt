package com.driveplayer.ui.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.driveplayer.ui.theme.AccentPrimary
import com.driveplayer.ui.theme.CardSurface
import com.driveplayer.ui.theme.SurfaceVariant
import com.driveplayer.ui.theme.TextPrimary
import com.driveplayer.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun AudioPanel(
    onDismiss: () -> Unit,
    availableAudioTracks: List<String>,
    selectedAudioTrack: Int,
    onAudioTrackChange: (Int, String) -> Unit,
    audioDelay: Float,
    onAudioDelayChange: (Float) -> Unit,
    currentVolume: Float,
    maxVolume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceVariant)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Audio",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── Audio Track Selection ───────────────────────────────────────────
        Text("Audio Track", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        
        if (availableAudioTracks.isEmpty()) {
            Text("No audio tracks available", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardSurface) // Theme-driven darker tile for the list
            ) {
                availableAudioTracks.forEachIndexed { index, name ->
                    val isSelected = selectedAudioTrack == index
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAudioTrackChange(index, name) }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = name,
                            color = if (isSelected) AccentPrimary else TextPrimary,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (isSelected) {
                            Text("Active", color = AccentPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (index < availableAudioTracks.lastIndex) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    }
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))

        // ── Audio Sync (Delay) ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Audio Sync", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            val delayStr = if (audioDelay > 0) "+${String.format("%.1f", audioDelay)}s" else "${String.format("%.1f", audioDelay)}s"
            Text(delayStr, color = AccentPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = audioDelay,
            onValueChange = onAudioDelayChange,
            valueRange = -2f..2f,
            steps = 39, // -2.0 to 2.0 with 0.1 increments
            colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-2s", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text("+2s", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(24.dp))

        // ── Volume Control ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Volume", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Text("${(currentVolume / maxVolume * 100).roundToInt()}%", color = AccentPrimary, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = currentVolume,
            onValueChange = onVolumeChange,
            valueRange = 0f..maxVolume,
            colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
        )

        Spacer(Modifier.height(32.dp))
    }
}
