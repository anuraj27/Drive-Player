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
import kotlin.math.roundToInt

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close

@Composable
fun SettingsController(
    activeTab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    onDismiss: () -> Unit,
    currentResizeMode: Int,
    onResizeModeChange: (Int) -> Unit,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitlesToggle: (Boolean) -> Unit,
    availableAudioTracks: List<String>,
    selectedAudioTrack: Int,
    onAudioTrackChange: (Int) -> Unit,
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
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activeTab != SettingsTab.MAIN_MENU) {
                IconButton(onClick = { onTabChange(SettingsTab.MAIN_MENU) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
            }
            Text(
                text = if (activeTab == SettingsTab.MAIN_MENU) "Player Settings" else getTabTitle(activeTab),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = if (activeTab == SettingsTab.MAIN_MENU) 8.dp else 0.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = TextPrimary)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (activeTab == SettingsTab.MAIN_MENU) {
            // ── Main Menu List ──────────────────────────────────────────────────
            MenuListItem("Playback Speed", onClick = { onTabChange(SettingsTab.SPEED) })
            MenuListItem("Resize Mode", onClick = { onTabChange(SettingsTab.RESIZE) })
            MenuListItem("Subtitles & Loop", onClick = { onTabChange(SettingsTab.SUBTITLES_LOOP) })
            if (availableAudioTracks.size > 1) {
                MenuListItem("Audio Tracks", onClick = { onTabChange(SettingsTab.AUDIO) })
            }
            MenuListItem("Video Filters", onClick = { onTabChange(SettingsTab.FILTERS) })
        }

        if (activeTab == SettingsTab.SPEED) {
            // ── Playback Speed ───────────────────────────────────────────────────
            val speedLabel = if (currentSpeed == currentSpeed.roundToInt().toFloat())
                "${currentSpeed.roundToInt()}x" else "${currentSpeed}x"
            Text("Speed: $speedLabel", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = currentSpeed,
                onValueChange = onSpeedChange,
                valueRange = 0.25f..3.0f,
                steps = 10,
                colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
            )
        }

        if (activeTab == SettingsTab.RESIZE) {
            // ── Resize Mode ──────────────────────────────────────────────────────
            Text("Resize Mode", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Fit"  to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                    "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ).forEach { (label, mode) ->
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
        }

        if (activeTab == SettingsTab.SUBTITLES_LOOP) {
            // ── Subtitles & Loop ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Subtitles", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = subtitlesEnabled,
                    onCheckedChange = onSubtitlesToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary, checkedTrackColor = AccentPrimary.copy(0.4f))
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Loop Video", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = isLooping,
                    onCheckedChange = onLoopingToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = AccentPrimary, checkedTrackColor = AccentPrimary.copy(0.4f))
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── A-B Loop ─────────────────────────────────────────────────────────
            Text("A-B Loop", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSetLoopStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (abLoopStart > 0L) AccentPrimary else SurfaceVariant
                    )
                ) { Text("Set A") }
                Button(
                    onClick = onSetLoopEnd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (abLoopEnd > 0L) AccentPrimary else SurfaceVariant
                    )
                ) { Text("Set B") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClearABLoop) { Text("Clear A-B Loop") }
        }

        if (activeTab == SettingsTab.AUDIO) {
            // ── Audio Tracks ─────────────────────────────────────────────────────
            if (availableAudioTracks.size > 1) {
                Text("Audio Track", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availableAudioTracks.forEachIndexed { index, name ->
                        FilterChip(
                            selected = selectedAudioTrack == index,
                            onClick = { onAudioTrackChange(index) },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentPrimary.copy(0.2f),
                                selectedLabelColor = AccentPrimary
                            )
                        )
                    }
                }
            } else {
                Text("No alternative audio tracks available.", color = TextSecondary)
            }
        }

        if (activeTab == SettingsTab.FILTERS) {
            // ── Video Filters ────────────────────────────────────────────────────
            val brightnessLabel = if (brightness < 0f) "System" else "${(brightness * 100).toInt()}%"
            Text("Brightness: $brightnessLabel", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = brightness.takeIf { it > 0f } ?: 0.5f,
                onValueChange = onBrightnessChange,
                valueRange = 0.01f..1f,
                colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
            )

            Text("Contrast: ${(contrast * 100).toInt()}%", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = contrast,
                onValueChange = onContrastChange,
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
            )

            Text("Saturation: ${(saturation * 100).toInt()}%", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = saturation,
                onValueChange = onSaturationChange,
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(activeTrackColor = AccentPrimary, thumbColor = AccentPrimary)
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun MenuListItem(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
    }
}

private fun getTabTitle(tab: SettingsTab): String {
    return when (tab) {
        SettingsTab.SPEED -> "Playback Speed"
        SettingsTab.RESIZE -> "Resize Mode"
        SettingsTab.SUBTITLES_LOOP -> "Subtitles & Loop"
        SettingsTab.AUDIO -> "Audio Tracks"
        SettingsTab.FILTERS -> "Video Filters"
        else -> "Settings"
    }
}
