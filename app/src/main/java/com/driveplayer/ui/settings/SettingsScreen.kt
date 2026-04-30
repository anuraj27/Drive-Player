package com.driveplayer.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.data.SettingsStore
import com.driveplayer.ui.theme.*

private const val GITHUB_URL = "https://github.com/anuraj27/online-video"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The hardware back button on the phone otherwise propagates straight to
    // Activity.onBackPressed() which finishes the app — Settings isn't a
    // real Activity, just a screen in our sealed-class navigation graph, so
    // we have to intercept here and route to onBack() the same way the
    // toolbar arrow does.
    BackHandler { onBack() }

    val versionName = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showClearSearchDialog  by remember { mutableStateOf(false) }
    var showSignOutDialog      by remember { mutableStateOf(false) }
    var showResetDialog        by remember { mutableStateOf(false) }
    var snackMessage           by remember { mutableStateOf<String?>(null) }

    // Equalizer preset names from libVLC. Resolved once (the list is static
    // for a given libVLC build). Kept as a (Int, String) list so we can also
    // expose the "None" sentinel at index -1 without indexing into the array.
    val eqPresets = remember {
        runCatching {
            val count = org.videolan.libvlc.MediaPlayer.Equalizer.getPresetCount()
            (0 until count).map { it to org.videolan.libvlc.MediaPlayer.Equalizer.getPresetName(it) }
        }.getOrDefault(emptyList())
    }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // ── Library ─────────────────────────────────────────────────────
            SectionHeader("Library")
            SectionCard {
                ChoiceRow(
                    title = "Default tab on launch",
                    subtitle = friendlyTabName(s.defaultHomeTab),
                    icon = Icons.Default.HomeWork,
                    options = listOf(
                        "LOCAL" to "Local",
                        "CLOUD" to "Cloud",
                        "DOWNLOADS" to "Downloads",
                    ),
                    selected = s.defaultHomeTab,
                    onSelect = vm::setDefaultHomeTab,
                )
            }

            // ── Appearance ──────────────────────────────────────────────────
            SectionHeader("Appearance")
            SectionCard {
                ChoiceRow(
                    title = "Theme",
                    subtitle = friendlyThemeMode(s.themeMode),
                    icon = Icons.Default.Palette,
                    options = listOf(
                        "SYSTEM" to "Match system",
                        "DARK" to "Dark",
                        "LIGHT" to "Light",
                    ),
                    selected = s.themeMode,
                    onSelect = vm::setThemeMode,
                )
                Divider()
                ChoiceRow(
                    title = "Hide controls after",
                    subtitle = "${s.controlsAutoHideMs / 1000} seconds",
                    icon = Icons.Default.Timer,
                    options = listOf(3_000L, 5_000L, 10_000L).map { it to "${it / 1000}s" },
                    selected = s.controlsAutoHideMs,
                    onSelect = vm::setControlsAutoHideMs,
                )
                Divider()
                SwitchRow(
                    title = "Show gesture hints",
                    subtitle = "Briefly overlay swipe / double-tap hints when a video starts",
                    icon = Icons.Default.TipsAndUpdates,
                    checked = s.showGestureHints,
                    onCheckedChange = vm::setShowGestureHints,
                )
            }

            // ── Playback ────────────────────────────────────────────────────
            SectionHeader("Playback")
            SectionCard {
                SwitchRow(
                    title = "Resume from last position",
                    subtitle = "Pick up where you left off when reopening a video",
                    icon = Icons.Default.PlayCircle,
                    checked = s.resumePlayback,
                    onCheckedChange = vm::setResumePlayback,
                )
                Divider()
                ChoiceRow(
                    title = "Default playback speed",
                    subtitle = formatSpeed(s.defaultPlaybackSpeed),
                    icon = Icons.Default.Speed,
                    options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to formatSpeed(it) },
                    selected = s.defaultPlaybackSpeed,
                    onSelect = vm::setDefaultPlaybackSpeed,
                )
                Divider()
                ChoiceRow(
                    title = "Skip duration",
                    subtitle = "${s.skipDurationMs / 1000} seconds",
                    icon = Icons.Default.Forward10,
                    options = listOf(5_000L, 10_000L, 15_000L, 30_000L).map { it to "${it / 1000}s" },
                    selected = s.skipDurationMs,
                    onSelect = vm::setSkipDurationMs,
                )
                Divider()
                ChoiceRow(
                    title = "Default orientation",
                    subtitle = friendlyOrientation(s.defaultOrientation),
                    icon = Icons.Default.ScreenRotation,
                    options = listOf(
                        "AUTO" to "Auto-rotate",
                        "LANDSCAPE" to "Landscape",
                        "PORTRAIT" to "Portrait",
                    ),
                    selected = s.defaultOrientation,
                    onSelect = vm::setDefaultOrientation,
                )
                Divider()
                SwitchRow(
                    title = "Repeat one",
                    subtitle = "Loop the current video instead of stopping at the end",
                    icon = Icons.Default.Repeat,
                    checked = s.repeatOne,
                    onCheckedChange = vm::setRepeatOne,
                )
                Divider()
                SwitchRow(
                    title = "Keep screen on",
                    subtitle = "Prevent the screen from sleeping while a video is open",
                    icon = Icons.Default.ScreenLockPortrait,
                    checked = s.keepScreenOn,
                    onCheckedChange = vm::setKeepScreenOn,
                )
                Divider()
                SliderRow(
                    title = "Network buffer",
                    subtitle = "${s.networkCacheMs} ms — higher smooths cellular jitter, slower first-frame",
                    icon = Icons.Default.NetworkWifi,
                    value = s.networkCacheMs.toFloat(),
                    range = 500f..5000f,
                    steps = 8,
                    onValueChange = { vm.setNetworkCacheMs(it.toInt()) },
                )
            }

            // ── Audio ───────────────────────────────────────────────────────
            SectionHeader("Audio")
            SectionCard {
                SliderRow(
                    title = "Volume boost",
                    subtitle = "${(s.volumeBoost * 100).toInt()}% — values above 100% can clip on quiet sources",
                    icon = Icons.Default.VolumeUp,
                    value = s.volumeBoost,
                    range = 1.0f..2.0f,
                    steps = 19,
                    onValueChange = { vm.setVolumeBoost(it) },
                )
                Divider()
                SwitchRow(
                    title = "Equalizer",
                    subtitle = "Apply the selected preset to every new playback",
                    icon = Icons.Default.GraphicEq,
                    checked = s.equalizerEnabled,
                    onCheckedChange = vm::setEqualizerEnabled,
                )
                if (s.equalizerEnabled && eqPresets.isNotEmpty()) {
                    Divider()
                    ChoiceRow(
                        title = "Equalizer preset",
                        subtitle = eqPresets.firstOrNull { it.first == s.equalizerPreset }?.second ?: "Flat",
                        icon = Icons.Default.Tune,
                        options = eqPresets,
                        selected = s.equalizerPreset.coerceAtLeast(0),
                        onSelect = vm::setEqualizerPreset,
                    )
                }
                Divider()
                SwitchRow(
                    title = "Background audio",
                    subtitle = "Keep audio playing when you leave the player or turn off the screen",
                    icon = Icons.Default.MusicNote,
                    checked = s.backgroundAudio,
                    onCheckedChange = vm::setBackgroundAudio,
                )
            }

            // ── Gestures ────────────────────────────────────────────────────
            SectionHeader("Player gestures")
            SectionCard {
                SwitchRow(
                    title = "Brightness swipe",
                    subtitle = "Vertical drag on the left half of the screen",
                    icon = Icons.Default.BrightnessMedium,
                    checked = s.brightnessGesture,
                    onCheckedChange = vm::setBrightnessGesture,
                )
                Divider()
                SwitchRow(
                    title = "Volume swipe",
                    subtitle = "Vertical drag on the right half of the screen",
                    icon = Icons.Default.VolumeUp,
                    checked = s.volumeGesture,
                    onCheckedChange = vm::setVolumeGesture,
                )
                Divider()
                SwitchRow(
                    title = "Seek swipe",
                    subtitle = "Horizontal drag to scrub through the timeline",
                    icon = Icons.Default.SwapHoriz,
                    checked = s.seekGesture,
                    onCheckedChange = vm::setSeekGesture,
                )
                Divider()
                SwitchRow(
                    title = "Double-tap to skip",
                    subtitle = "Double-tap left/right to skip back / forward",
                    icon = Icons.Default.TouchApp,
                    checked = s.doubleTapSeek,
                    onCheckedChange = vm::setDoubleTapSeek,
                )
                Divider()
                SwitchRow(
                    title = "Pinch to zoom",
                    subtitle = "Spread two fingers to zoom into the picture",
                    icon = Icons.Default.ZoomIn,
                    checked = s.pinchZoom,
                    onCheckedChange = vm::setPinchZoom,
                )
            }

            // ── Subtitles ───────────────────────────────────────────────────
            SectionHeader("Subtitles")
            SectionCard {
                SwitchRow(
                    title = "Subtitles enabled by default",
                    subtitle = "Show the embedded subtitle track when a video opens",
                    icon = Icons.Default.Subtitles,
                    checked = s.subtitlesEnabledByDefault,
                    onCheckedChange = vm::setSubtitlesEnabledByDefault,
                )
                Divider()
                SwitchRow(
                    title = "Auto-load same-folder subtitles",
                    subtitle = "Attach a matching .srt sitting next to the video",
                    icon = Icons.Default.ClosedCaption,
                    checked = s.autoLoadSubtitles,
                    onCheckedChange = vm::setAutoLoadSubtitles,
                )
                Divider()
                SliderRow(
                    title = "Default text size",
                    subtitle = "${s.defaultSubtitleScale}%",
                    icon = Icons.Default.FormatSize,
                    value = s.defaultSubtitleScale.toFloat(),
                    range = 50f..200f,
                    steps = 14,
                    onValueChange = { vm.setDefaultSubtitleScale(it.toInt()) },
                )
                Divider()
                ColorRow(
                    title = "Default text colour",
                    selected = s.defaultSubtitleColor,
                    onSelect = vm::setDefaultSubtitleColor,
                )
                Divider()
                SliderRow(
                    title = "Default background opacity",
                    subtitle = "${(s.defaultSubtitleBgAlpha * 100 / 255)}%",
                    icon = Icons.Default.FormatPaint,
                    value = s.defaultSubtitleBgAlpha.toFloat(),
                    range = 0f..255f,
                    steps = 0,
                    onValueChange = { vm.setDefaultSubtitleBgAlpha(it.toInt()) },
                )
            }

            // ── Downloads ───────────────────────────────────────────────────
            SectionHeader("Downloads")
            SectionCard {
                SwitchRow(
                    title = "Download over Wi-Fi only",
                    subtitle = "Pause queued downloads on cellular data",
                    icon = Icons.Default.Wifi,
                    checked = s.downloadsWifiOnly,
                    onCheckedChange = vm::setDownloadsWifiOnly,
                )
                Divider()
                ChoiceRow(
                    title = "Auto-delete completed downloads",
                    subtitle = friendlyAutoDelete(s.autoDeleteDownloadsDays),
                    icon = Icons.Default.AutoDelete,
                    options = listOf(
                        0 to "Never",
                        1 to "After 1 day",
                        7 to "After 7 days",
                        30 to "After 30 days",
                    ),
                    selected = s.autoDeleteDownloadsDays,
                    onSelect = vm::setAutoDeleteDownloadsDays,
                )
            }

            // ── Advanced ────────────────────────────────────────────────────
            SectionHeader("Advanced")
            SectionCard {
                ChoiceRow(
                    title = "Hardware acceleration",
                    subtitle = if (s.hardwareAcceleration == "AUTO") "Automatic (recommended)" else "Disabled — software decoder",
                    icon = Icons.Default.Memory,
                    options = listOf(
                        "AUTO" to "Automatic",
                        "DISABLED" to "Disabled",
                    ),
                    selected = s.hardwareAcceleration,
                    onSelect = vm::setHardwareAcceleration,
                )
            }

            // ── Privacy ─────────────────────────────────────────────────────
            SectionHeader("Privacy")
            SectionCard {
                ActionRow(
                    title = "Clear watch history",
                    subtitle = "Removes the Continue Watching list",
                    icon = Icons.Default.HistoryToggleOff,
                    onClick = { showClearHistoryDialog = true },
                )
                Divider()
                ActionRow(
                    title = "Clear search history",
                    subtitle = "Removes recent searches on both Local and Cloud",
                    icon = Icons.Default.SearchOff,
                    onClick = { showClearSearchDialog = true },
                )
                Divider()
                ActionRow(
                    title = "Sign out of all accounts",
                    subtitle = "You'll need to sign in again to access Drive",
                    icon = Icons.Default.Logout,
                    destructive = true,
                    onClick = { showSignOutDialog = true },
                )
                Divider()
                ActionRow(
                    title = "Reset all settings",
                    subtitle = "Restore every preference to its default value",
                    icon = Icons.Default.Restore,
                    destructive = true,
                    onClick = { showResetDialog = true },
                )
            }

            // ── About ───────────────────────────────────────────────────────
            SectionHeader("About")
            SectionCard {
                InfoRow(
                    title = "Version",
                    subtitle = versionName,
                    icon = Icons.Default.Info,
                )
                Divider()
                ActionRow(
                    title = "View source on GitHub",
                    subtitle = GITHUB_URL,
                    icon = Icons.Default.Code,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
        }
    }

    if (showClearHistoryDialog) {
        ConfirmDialog(
            title = "Clear watch history?",
            message = "Your Continue Watching list will be removed. Saved playback positions are NOT affected.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                vm.clearWatchHistory()
                showClearHistoryDialog = false
                snackMessage = "Watch history cleared"
            },
            onDismiss = { showClearHistoryDialog = false },
        )
    }

    if (showClearSearchDialog) {
        ConfirmDialog(
            title = "Clear search history?",
            message = "All recent searches on the Local and Cloud tabs will be removed.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                vm.clearSearchHistory()
                showClearSearchDialog = false
                snackMessage = "Search history cleared"
            },
            onDismiss = { showClearSearchDialog = false },
        )
    }

    if (showSignOutDialog) {
        ConfirmDialog(
            title = "Sign out of all accounts?",
            message = "Every saved Google account will be removed from this app. This won't affect the accounts on your device.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                vm.signOutFromAllAccounts()
                showSignOutDialog = false
                snackMessage = "Signed out of all accounts"
            },
            onDismiss = { showSignOutDialog = false },
        )
    }

    if (showResetDialog) {
        ConfirmDialog(
            title = "Reset all settings?",
            message = "Every preference (theme, gestures, playback, downloads, …) will be restored to its default value. Watch history, search history, and accounts are NOT affected.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = {
                vm.resetAllSettings()
                showResetDialog = false
                snackMessage = "Settings restored to defaults"
            },
            onDismiss = { showResetDialog = false },
        )
    }
}

// ── Building blocks ────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        color = SurfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        color = TextMuted.copy(alpha = 0.12f),
        thickness = 0.5.dp,
    )
}

@Composable
private fun RowFrame(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    iconTint: Color = AccentPrimary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    titleColor: Color = TextPrimary,
    title: String,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    RowFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentPrimary,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = SurfaceVariant,
                    uncheckedBorderColor = TextMuted,
                ),
            )
        },
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    RowFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { open = true },
        trailing = {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
        },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title, color = TextPrimary) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(value)
                                    open = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            RadioButton(
                                selected = value == selected,
                                onClick = {
                                    onSelect(value)
                                    open = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentPrimary,
                                    unselectedColor = TextMuted,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceVariant,
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AccentPrimary,
                activeTrackColor = AccentPrimary,
                inactiveTrackColor = TextMuted.copy(alpha = 0.25f),
                inactiveTickColor = Color.Transparent,
                activeTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun ColorRow(
    title: String,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val palette = remember {
        listOf(
            0xFFFFFF to "White",
            0xFFEB3B to "Yellow",
            0x00E5FF to "Cyan",
            0x76FF03 to "Green",
            0xFF4081 to "Pink",
            0xFF1744 to "Red",
        )
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentPrimary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Palette, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    palette.firstOrNull { it.first == selected }?.second ?: "Custom",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            palette.forEach { (rgb, _) ->
                val isSelected = rgb == selected
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 36.dp else 30.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF000000.toInt() or rgb))
                        .clickable { onSelect(rgb) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    RowFrame(
        icon = icon,
        iconTint = if (destructive) ColorError else AccentPrimary,
        title = title,
        titleColor = if (destructive) ColorError else TextPrimary,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted)
        },
    )
}

@Composable
private fun InfoRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    RowFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = { Text(message, color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) ColorError else AccentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = SurfaceVariant,
    )
}

// ── Helpers ────────────────────────────────────────────────────────────────

private fun friendlyTabName(value: String): String = when (value) {
    "LOCAL" -> "Local"
    "CLOUD" -> "Cloud"
    "DOWNLOADS" -> "Downloads"
    else -> value
}

private fun friendlyThemeMode(value: String): String = when (value) {
    "SYSTEM" -> "Match system"
    "DARK"   -> "Dark"
    "LIGHT"  -> "Light"
    else     -> value
}

private fun friendlyOrientation(value: String): String = when (value) {
    "AUTO"      -> "Auto-rotate"
    "LANDSCAPE" -> "Landscape"
    "PORTRAIT"  -> "Portrait"
    else        -> value
}

private fun friendlyAutoDelete(days: Int): String = when (days) {
    0    -> "Never"
    1    -> "After 1 day"
    else -> "After $days days"
}

private fun formatSpeed(speed: Float): String {
    // 1.0 → "1×"; 1.25 → "1.25×"; 0.5 → "0.5×"
    val str = "%.2f".format(speed).trimEnd('0').trimEnd('.')
    return "${str}×"
}
