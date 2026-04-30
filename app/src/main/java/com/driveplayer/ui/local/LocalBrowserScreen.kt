package com.driveplayer.ui.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.local.LocalVideoRepository
import com.driveplayer.data.local.VideoFolder
import com.driveplayer.image.FolderCollageThumbnail
import com.driveplayer.image.LocalVideoThumbnail
import com.driveplayer.ui.common.MediaGridCard
import com.driveplayer.ui.common.TopBarOverflow
import com.driveplayer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocalBrowserScreen(
    onVideoClick: (LocalVideo) -> Unit,
    localRepo: LocalVideoRepository,
    onOpenSettings: () -> Unit = {},
    vm: LocalBrowserViewModel = viewModel(factory = LocalBrowserViewModel.Factory(localRepo))
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val isInFolder by vm.isInFolder.collectAsStateWithLifecycle()
    val isSearchActive by vm.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery    by vm.searchQuery.collectAsStateWithLifecycle()
    val searchState    by vm.searchState.collectAsStateWithLifecycle()
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()

    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) runCatching { searchFocusRequester.requestFocus() }
    }

    // Permission handling
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) vm.loadFolders()
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            permLauncher.launch(requiredPermission)
        }
    }

    // BackHandler must intercept search BEFORE folder navigation — vm.goBack()
    // already implements that priority, but enabling the handler whenever
    // either is "back-able" is essential or system-back will leave the app.
    val canGoBack = isSearchActive || isInFolder
    androidx.activity.compose.BackHandler(enabled = canGoBack) { vm.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { vm.setSearchQuery(it) },
                            singleLine = true,
                            placeholder = { Text("Search videos…", color = TextMuted) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { /* debounce already running */ }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = AccentPrimary,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { vm.setSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                                    }
                                }
                            },
                        )
                    } else {
                        Text(
                            when (val s = state) {
                                is LocalBrowserState.Videos -> s.folderName
                                else -> "Local Videos"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = { vm.deactivateSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search", tint = TextPrimary)
                        }
                    } else if (isInFolder) {
                        IconButton(onClick = { vm.goBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    }
                },
                actions = {
                    if (!isSearchActive) {
                        IconButton(onClick = { vm.activateSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                        }
                        // List/grid toggle. Icon shows the *target* layout
                        // (i.e. tap "GridView" to enter grid mode, then it
                        // flips to a list icon so a second tap returns to
                        // the list). Same convention Drive / Photos use.
                        @Suppress("DEPRECATION")
                        val viewListIcon = Icons.Default.ViewList
                        IconButton(onClick = { vm.toggleViewMode() }) {
                            Icon(
                                imageVector = if (viewMode == "GRID")
                                    viewListIcon
                                else Icons.Default.GridView,
                                contentDescription = if (viewMode == "GRID") "Show as list" else "Show as grid",
                                tint = TextSecondary,
                            )
                        }
                        TopBarOverflow(
                            onOpenSettings = onOpenSettings,
                            onRefresh = { vm.refresh() },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasPermission) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Storage permission required", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Grant permission to browse local videos", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { permLauncher.launch(requiredPermission) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) { Text("Grant Permission") }
                }
            } else if (isSearchActive) {
                LocalSearchContent(
                    query = searchQuery,
                    searchState = searchState,
                    recentSearches = recentSearches,
                    positions = positions,
                    viewMode = viewMode,
                    onRecentClick = { vm.setSearchQuery(it) },
                    onRecentRemove = { vm.removeRecentSearch(it) },
                    onClearRecents = { vm.clearRecentSearches() },
                    onVideoClick = onVideoClick,
                )
            } else {
                BrowseContent(
                    state = state,
                    positions = positions,
                    viewMode = viewMode,
                    onOpenFolder = { vm.openFolder(it) },
                    onVideoClick = onVideoClick,
                    onRetry = { vm.refresh() },
                )
            }
        }
    }
}

@Composable
private fun BrowseContent(
    state: LocalBrowserState,
    positions: Map<String, Long>,
    viewMode: String,
    onOpenFolder: (VideoFolder) -> Unit,
    onVideoClick: (LocalVideo) -> Unit,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is LocalBrowserState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = AccentPrimary
                )
            }

            is LocalBrowserState.Error -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Warning, null, tint = ColorError, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(s.message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)
                    ) { Text("Retry") }
                }
            }

            is LocalBrowserState.Folders -> {
                if (s.folders.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.VideoLibrary, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No videos found", color = TextMuted, style = MaterialTheme.typography.bodyLarge)
                    }
                } else if (viewMode == "GRID") {
                    LazyVerticalGrid(
                        // 168dp min cell yields 2 columns on small phones,
                        // 3 on a 6"+ device, 4 on a foldable / tablet — chosen
                        // so a 16:9 thumbnail still reads at ~110-150dp tall.
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(s.folders, key = { it.path }) { folder ->
                            MediaGridCard(
                                title = folder.name,
                                subtitle = "${folder.videoCount} video${if (folder.videoCount != 1) "s" else ""}",
                                progressFraction = 0f,
                                qualityLabel = null,
                                onClick = { onOpenFolder(folder) },
                                thumbnail = {
                                    FolderCollageThumbnail(folder = folder, modifier = Modifier.fillMaxSize())
                                },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(s.folders, key = { it.path }) { folder ->
                            FolderItem(folder) { onOpenFolder(folder) }
                        }
                    }
                }
            }

            is LocalBrowserState.Videos -> {
                if (s.videos.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = TextMuted, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No videos in this folder", color = TextMuted)
                    }
                } else if (viewMode == "GRID") {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(s.videos, key = { it.id }) { video ->
                            MediaGridCard(
                                title = video.title,
                                subtitle = "${video.formattedDuration}  ·  ${video.formattedSize}",
                                progressFraction = progressFractionFor(video, positions),
                                qualityLabel = video.qualityLabel,
                                onClick = { onVideoClick(video) },
                                thumbnail = { LocalVideoThumbnail(video = video, modifier = Modifier.fillMaxSize()) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(s.videos, key = { it.id }) { video ->
                            VideoItem(
                                video = video,
                                showFolder = false,
                                progressFraction = progressFractionFor(video, positions),
                                onClick = { onVideoClick(video) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalSearchContent(
    query: String,
    searchState: LocalSearchState?,
    recentSearches: List<String>,
    positions: Map<String, Long>,
    viewMode: String,
    onRecentClick: (String) -> Unit,
    onRecentRemove: (String) -> Unit,
    onClearRecents: () -> Unit,
    onVideoClick: (LocalVideo) -> Unit,
) {
    when {
        query.isBlank() -> {
            if (recentSearches.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Type to search your local videos", color = TextMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Matches title, folder, or path.",
                        color = TextMuted.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                RecentLocalSearches(
                    recents = recentSearches,
                    onPick = onRecentClick,
                    onRemove = onRecentRemove,
                    onClear = onClearRecents,
                )
            }
        }

        searchState == null || searchState is LocalSearchState.Loading -> {
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center),
                color = AccentPrimary,
            )
        }

        searchState is LocalSearchState.Error -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = ColorError, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(searchState.message, color = TextSecondary)
            }
        }

        searchState is LocalSearchState.Success -> {
            if (searchState.videos.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No videos found for \"$query\"", color = TextMuted)
                }
            } else if (viewMode == "GRID") {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(searchState.videos, key = { it.id }) { video ->
                        MediaGridCard(
                            title = video.title,
                            subtitle = "${video.formattedDuration}  ·  ${video.folderName}",
                            progressFraction = progressFractionFor(video, positions),
                            qualityLabel = video.qualityLabel,
                            onClick = { onVideoClick(video) },
                            thumbnail = { LocalVideoThumbnail(video = video, modifier = Modifier.fillMaxSize()) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item(key = "result_count") {
                        Text(
                            "${searchState.videos.size} result${if (searchState.videos.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(searchState.videos, key = { it.id }) { video ->
                        // showFolder = true so the user can disambiguate
                        // identically-named files across different folders.
                        VideoItem(
                            video = video,
                            showFolder = true,
                            progressFraction = progressFractionFor(video, positions),
                            onClick = { onVideoClick(video) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentLocalSearches(
    recents: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Recent searches",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClear) {
                Text("Clear", color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recents.forEach { q ->
                AssistChip(
                    onClick = { onPick(q) },
                    label = { Text(q, color = TextPrimary) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    },
                    trailingIcon = {
                        IconButton(onClick = { onRemove(q) }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(14.dp))
                        }
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = SurfaceVariant,
                        labelColor = TextPrimary,
                    ),
                    border = null,
                )
            }
        }
    }
}

@Composable
private fun FolderItem(folder: VideoFolder, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 96x54 (16:9) collage replaces the previous solid-tinted Folder icon.
        // Builds straight from the folder's own videos so users recognise the
        // content without opening the folder.
        FolderCollageThumbnail(
            folder = folder,
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp),
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${folder.videoCount} video${if (folder.videoCount != 1) "s" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }

        Icon(Icons.Default.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun VideoItem(
    video: LocalVideo,
    showFolder: Boolean,
    progressFraction: Float,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail + overlays in a single Box so the quality chip + progress
        // bar can be positioned relative to the bitmap. 96x54 keeps a clean
        // 16:9 aspect for landscape content; portrait clips letterbox.
        Box(
            modifier = Modifier.size(width = 96.dp, height = 54.dp),
        ) {
            LocalVideoThumbnail(
                video = video,
                modifier = Modifier.fillMaxSize(),
            )
            // Quality chip (e.g. "1080p") in the top-right. Hidden when the
            // MediaStore index didn't populate width/height for this clip.
            video.qualityLabel?.let { label ->
                QualityChip(
                    text = label,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
            // Watch-progress line — only when the user has started this video.
            // 5 s threshold (mirroring PlaybackPositionStore.save) hides
            // accidental open-and-close noise.
            if (progressFraction > 0f) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                    color = AccentPrimary,
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(video.formattedDuration)
                    append("  ·  ")
                    append(video.formattedSize)
                    if (showFolder) {
                        append("  ·  ")
                        append(video.folderName)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun QualityChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Look up the watched-fraction (0f..1f) for [video] in a positions map keyed
 * the same way as [com.driveplayer.player.PlaybackPositionStore]. Returns 0f
 * when there's no entry, the saved position is below the 5s threshold, or
 * the duration is unknown.
 */
internal fun progressFractionFor(video: LocalVideo, positions: Map<String, Long>): Float {
    val key = video.positionKey ?: if (video.id >= 0) "local_${video.id}" else null
    val saved = key?.let { positions[it] } ?: return 0f
    if (saved <= 5_000L) return 0f
    val dur = video.duration
    if (dur <= 0L) return 0f
    return (saved.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
}
