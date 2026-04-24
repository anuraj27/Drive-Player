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
import com.driveplayer.ui.theme.SurfaceVariant
import com.driveplayer.ui.theme.TextPrimary
import com.driveplayer.ui.theme.TextSecondary

@Composable
fun SubtitlePanel(
    onDismiss: () -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesToggle: (Boolean) -> Unit,
    availableSubtitleTracks: List<String>,
    selectedSubtitleTrack: Int,
    onSubtitleTrackChange: (Int, String) -> Unit,
    onLoadExternalSubtitle: () -> Unit,
    subtitleDelay: Float,
    onSubtitleDelayChange: (Float) -> Unit,
    subtitlePosition: String,
    onSubtitlePositionChange: (String) -> Unit
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
                text = "Subtitles",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── Toggle Section ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Subtitles", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = subtitlesEnabled,
                onCheckedChange = onSubtitlesToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary, checkedTrackColor = AccentPrimary.copy(0.4f))
            )
        }
        Spacer(Modifier.height(16.dp))

        if (subtitlesEnabled) {
            // ── Subtitle Track List ─────────────────────────────────────────────
            Text("Subtitle Track", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            
            if (availableSubtitleTracks.isEmpty()) {
                Text("No subtitles available", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2C2C2C))
                ) {
                    availableSubtitleTracks.forEachIndexed { index, name ->
                        val isSelected = selectedSubtitleTrack == index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSubtitleTrackChange(index, name) }
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
                        if (index < availableSubtitleTracks.lastIndex) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onLoadExternalSubtitle,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load subtitle file (.srt)", color = TextPrimary)
            }

            Spacer(Modifier.height(24.dp))

            // ── Subtitle Sync (Delay) ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Subtitle Sync", color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                val delayStr = if (subtitleDelay > 0) "+${String.format("%.1f", subtitleDelay)}s" else "${String.format("%.1f", subtitleDelay)}s"
                Text(delayStr, color = AccentPrimary, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = subtitleDelay,
                onValueChange = onSubtitleDelayChange,
                valueRange = -5f..5f,
                steps = 99, // -5.0 to 5.0 with 0.1 increments
                colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("-5s", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                Text("+5s", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(24.dp))

            // ── Subtitle Position ───────────────────────────────────────────────
            Text("Position", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Bottom", "Slightly Above").forEach { pos ->
                    FilterChip(
                        selected = subtitlePosition == pos,
                        onClick = { onSubtitlePositionChange(pos) },
                        label = { Text(pos) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentPrimary.copy(0.2f),
                            selectedLabelColor = AccentPrimary
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
