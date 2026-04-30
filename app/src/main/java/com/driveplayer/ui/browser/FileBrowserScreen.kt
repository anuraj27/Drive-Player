package com.driveplayer.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
// Both `items` extensions live under different scope receivers
// (LazyListScope and LazyGridScope), so the compiler disambiguates by
// receiver — we can import both.
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
import com.driveplayer.image.CloudFileThumbnail
import com.driveplayer.image.CloudFolderCollageThumbnail
import com.driveplayer.image.EmptyFolderThumbnail
import com.driveplayer.image.withDriveSize
import com.driveplayer.player.PinnedFolder
import com.driveplayer.player.WatchEntry
import com.driveplayer.ui.cloud.SavedAccount
import com.driveplayer.ui.common.MediaGridCard
import com.driveplayer.ui.common.TopBarOverflow
import com.driveplayer.ui.theme.*
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    repo: DriveRepository,
    accessToken: String,
    accountEmail: String,
    displayName: String?,
    savedAccounts: List<SavedAccount>,
    onSwitchAccount: (SavedAccount) -> Unit,
    onAddAccount: () -> Unit,
    onVideoClick: (file: DriveFile, siblings: List<DriveFile>) -> Unit,
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit = {},
    vm: FileBrowserViewModel = viewModel(key = accountEmail, factory = FileBrowserViewModel.Factory(repo, accessToken))
) {
    val state           by vm.state.collectAsStateWithLifecycle()
    val folderStack     by vm.folderStack.collectAsStateWithLifecycle()
    val tabMode         by vm.tabMode.collectAsStateWithLifecycle()
    val isSearchActive    by vm.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery       by vm.searchQuery.collectAsStateWithLifecycle()
    val searchState       by vm.searchState.collectAsStateWithLifecycle()
    val recentSearches    by vm.recentSearches.collectAsStateWithLifecycle()
    val pinnedFolders     by vm.pinnedFolders.collectAsStateWithLifecycle()
    val recentlyWatched   by vm.recentlyWatched.collectAsStateWithLifecycle()
    val downloadedFileIds by vm.downloadedFileIds.collectAsStateWithLifecycle()
    val downloadingFileIds by vm.downloadingFileIds.collectAsStateWithLifecycle()
    val positions by vm.positions.collectAsStateWithLifecycle()
    val folderThumbnails by vm.folderThumbnails.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()

    var showAccountMenu by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Navigate to a recently-watched file. If the entry remembers its parent folder
    // (saved by the player when it last checkpointed history), we refetch siblings so
    // an external `.srt` in the same folder can still auto-attach. We don't block the
    // navigation on that fetch — we open the player immediately if no parent is known.
    fun openWatchEntry(entry: WatchEntry) {
        val synthetic = DriveFile(
            id = entry.fileId,
            name = entry.title,
            mimeType = entry.mimeType,
            thumbnailLink = entry.thumbnailLink,
        )
        val parent = entry.parentFolderId
        if (parent != null) {
            coroutineScope.launch {
                val siblings = repo.listFolder(parent).getOrElse { emptyList() }
                onVideoClick(synthetic, siblings)
            }
        } else {
            onVideoClick(synthetic, emptyList())
        }
    }

    val canGoBack = isSearchActive || folderStack.size > 1
    BackHandler(enabled = canGoBack) { vm.goBack() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val isAtRoot = folderStack.size <= 1

    Scaffold(
        topBar = {
            // Single consolidated top bar:
            //  • Search active        → back-arrow + search field + clear
            //  • At root (My Drive / Shared) → tab row + actions
            //  • In a subfolder       → back-arrow + folder title (with breadcrumb) + actions
            //
            // Action icons (search, refresh, profile, settings ⋮) live in ONE
            // place so they're never duplicated regardless of which mode we're in.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    isSearchActive -> {
                        IconButton(onClick = { vm.deactivateSearch() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel search", tint = TextPrimary)
                        }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { vm.setSearchQuery(it) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp)
                                .focusRequester(searchFocusRequester),
                            placeholder = { Text("Search videos…", color = TextMuted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentPrimary,
                                unfocusedBorderColor = TextMuted,
                                cursorColor = AccentPrimary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { /* debounce already fires */ }),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { vm.setSearchQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted)
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    isAtRoot -> {
                        // Compact tab row — "My Drive" / "Shared" as the root heading.
                        TabRow(
                            modifier = Modifier.weight(1f),
                            selectedTabIndex = if (tabMode == TabMode.MY_DRIVE) 0 else 1,
                            containerColor = DarkBackground,
                            contentColor = AccentPrimary,
                        ) {
                            Tab(
                                selected = tabMode == TabMode.MY_DRIVE,
                                onClick = { vm.switchTab(TabMode.MY_DRIVE) },
                                text = {
                                    Text(
                                        "My Drive",
                                        fontWeight = if (tabMode == TabMode.MY_DRIVE) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                            Tab(
                                selected = tabMode == TabMode.SHARED,
                                onClick = { vm.switchTab(TabMode.SHARED) },
                                text = {
                                    Text(
                                        "Shared",
                                        fontWeight = if (tabMode == TabMode.SHARED) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                    else -> {
                        // In a subfolder — back-arrow plus the folder title and breadcrumb.
                        IconButton(onClick = { vm.goBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(
                                vm.currentFolder.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                folderStack.dropLast(1).joinToString(" › ") { it.name },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Action icons — same set in every non-search mode so root and
                // subfolders feel consistent. Hidden during search to give the
                // text field room. Refresh now lives inside the overflow menu
                // so the row stays compact at smaller widths.
                if (!isSearchActive) {
                    IconButton(onClick = { vm.activateSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                    }
                    // List/grid toggle — sits between the search button and
                    // the account avatar. Same icon-flip convention as
                    // LocalBrowserScreen so both browse tabs feel uniform.
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
                    Box {
                        IconButton(onClick = { showAccountMenu = true }) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AccentPrimary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    (displayName?.firstOrNull() ?: accountEmail.firstOrNull())
                                        ?.uppercaseChar()?.toString() ?: "?",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        AccountDropdownMenu(
                            expanded = showAccountMenu,
                            onDismiss = { showAccountMenu = false },
                            currentEmail = accountEmail,
                            currentDisplayName = displayName,
                            savedAccounts = savedAccounts,
                            onSwitchAccount = { showAccountMenu = false; onSwitchAccount(it) },
                            onAddAccount = { showAccountMenu = false; onAddAccount() },
                            onLogout = { showAccountMenu = false; onLogout() },
                        )
                    }
                    TopBarOverflow(
                        onOpenSettings = onOpenSettings,
                        onRefresh = { vm.refresh() },
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isSearchActive) {
                SearchResultsContent(
                    query = searchQuery,
                    searchState = searchState,
                    recentSearches = recentSearches,
                    downloadedFileIds = downloadedFileIds,
                    downloadingFileIds = downloadingFileIds,
                    positions = positions,
                    viewMode = viewMode,
                    onRecentClick = { vm.setSearchQuery(it) },
                    onRecentRemove = { vm.removeRecentSearch(it) },
                    onClearRecents = { vm.clearRecentSearches() },
                    onVideoClick = { file ->
                        // Refetch the parent folder so an external `.srt` next to
                        // the video is still picked up by the player. Don't block
                        // the navigation on it — open an empty-sibling player if
                        // the fetch fails or no parent is available.
                        coroutineScope.launch {
                            val siblings = vm.fetchSiblingsFor(file)
                            onVideoClick(file, siblings)
                        }
                    },
                    onDownload = { vm.downloadFile(it) },
                )
            } else {
                BrowseContent(
                    state = state,
                    folderStack = folderStack,
                    tabMode = tabMode,
                    recentlyWatched = recentlyWatched,
                    pinnedFolders = pinnedFolders,
                    downloadedFileIds = downloadedFileIds,
                    downloadingFileIds = downloadingFileIds,
                    positions = positions,
                    folderThumbnails = folderThumbnails,
                    viewMode = viewMode,
                    onEnsureFolderThumbnails = { vm.ensureFolderThumbnails(it) },
                    onWatchEntryClick = { entry -> openWatchEntry(entry) },
                    onPinnedFolderClick = { vm.navigateToPinnedFolder(it) },
                    onFileClick = { file, siblings ->
                        if (file.isFolder) vm.openFolder(file)
                        else if (file.isVideo) onVideoClick(file, siblings)
                    },
                    onTogglePin = { vm.togglePin(it) },
                    onDownload = { vm.downloadFile(it) },
                    onRetry = { vm.refresh() }
                )
            }
        }
    }
}

// ── Browse content ─────────────────────────────────────────────────────────────

@Composable
private fun BrowseContent(
    state: BrowserState,
    folderStack: List<FolderEntry>,
    tabMode: TabMode,
    recentlyWatched: List<WatchEntry>,
    pinnedFolders: List<PinnedFolder>,
    downloadedFileIds: Set<String>,
    downloadingFileIds: Set<String>,
    positions: Map<String, Long>,
    folderThumbnails: Map<String, List<String>>,
    viewMode: String,
    onEnsureFolderThumbnails: (String) -> Unit,
    onWatchEntryClick: (WatchEntry) -> Unit,
    onPinnedFolderClick: (PinnedFolder) -> Unit,
    onFileClick: (DriveFile, List<DriveFile>) -> Unit,
    onTogglePin: (DriveFile) -> Unit,
    onDownload: (DriveFile) -> Unit,
    onRetry: () -> Unit,
) {
    val isAtRoot = folderStack.size == 1 && tabMode == TabMode.MY_DRIVE

    Box(modifier = Modifier.fillMaxSize()) {
    when (val s = state) {
        is BrowserState.Loading -> {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = AccentPrimary)
        }

        is BrowserState.Error -> {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = ColorError, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(s.message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)) {
                    Text("Retry")
                }
            }
        }

        is BrowserState.Success -> {
            // Render only folders + videos. The repository deliberately also
            // returns `.srt` files in `s.files` so the player can auto-attach
            // an external subtitle that lives next to the video — but those
            // sidecar files have no business taking up space in the user-
            // facing browse list. We pass the unfiltered `s.files` as the
            // sibling argument to onFileClick so the auto-attach lookup
            // still has access to the subtitles.
            val visibleFiles = s.files.filter { it.isFolder || it.isVideo }
            if (viewMode == "GRID") {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The horizontal "Continue Watching" + "Pinned Folders"
                    // strips are inherently row-shaped and don't make sense
                    // tiled inside the grid, so we span them across all
                    // columns and let the per-tile cards live in the proper
                    // grid below them.
                    if (isAtRoot && recentlyWatched.isNotEmpty()) {
                        item(key = "cw_header", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader("Continue Watching")
                        }
                        item(key = "cw_row", span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(recentlyWatched, key = { it.fileId }) { entry ->
                                    WatchCard(entry = entry, onClick = { onWatchEntryClick(entry) })
                                }
                            }
                        }
                    }
                    if (isAtRoot && pinnedFolders.isNotEmpty()) {
                        item(key = "pin_header", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader("Pinned Folders")
                        }
                        item(key = "pin_row", span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(pinnedFolders, key = { it.id }) { pinned ->
                                    PinnedFolderChip(
                                        folder = pinned,
                                        onClick = { onPinnedFolderClick(pinned) },
                                    )
                                }
                            }
                        }
                    }
                    if (visibleFiles.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("No videos in this folder", color = TextMuted)
                            }
                        }
                    } else {
                        items(
                            items = visibleFiles,
                            key = { it.id },
                        ) { file ->
                            if (file.isFolder) {
                                LaunchedEffect(file.id) { onEnsureFolderThumbnails(file.id) }
                            }
                            // Subtitle composition mirrors the list rows so
                            // toggling layout doesn't change what info shows.
                            val subtitle = buildString {
                                if (file.isVideo) {
                                    file.formattedDuration?.let { append(it) }
                                    if (file.formattedSize.isNotEmpty()) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append(file.formattedSize)
                                    }
                                } else if (file.isFolder) {
                                    val childCount = folderThumbnails[file.id]?.size
                                    if (childCount != null && childCount > 0) {
                                        append("$childCount video${if (childCount != 1) "s" else ""}")
                                    } else {
                                        append("Folder")
                                    }
                                }
                            }
                            MediaGridCard(
                                title = file.name,
                                subtitle = subtitle,
                                progressFraction = if (file.isVideo) progressFractionFor(file, positions) else 0f,
                                qualityLabel = if (file.isVideo) file.qualityLabel else null,
                                isPinned = pinnedFolders.any { it.id == file.id },
                                onClick = { onFileClick(file, s.files) },
                                onLongClick = { if (file.isFolder) onTogglePin(file) },
                                thumbnail = {
                                    when {
                                        file.isFolder -> {
                                            val thumbs = folderThumbnails[file.id]
                                            if (thumbs.isNullOrEmpty()) {
                                                EmptyFolderThumbnail(modifier = Modifier.fillMaxSize())
                                            } else {
                                                CloudFolderCollageThumbnail(
                                                    childThumbnailUrls = thumbs,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                        file.isVideo -> {
                                            CloudFileThumbnail(
                                                file = file,
                                                sizeHintPx = 400,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        else -> EmptyFolderThumbnail(modifier = Modifier.fillMaxSize())
                                    }
                                },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    // ── Continue Watching carousel ─────────────────────────────
                    if (isAtRoot && recentlyWatched.isNotEmpty()) {
                        item(key = "continue_watching_header") {
                            SectionHeader("Continue Watching")
                        }
                        item(key = "continue_watching_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(recentlyWatched, key = { it.fileId }) { entry ->
                                    WatchCard(entry = entry, onClick = { onWatchEntryClick(entry) })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    // ── Pinned Folders row ─────────────────────────────────────
                    if (isAtRoot && pinnedFolders.isNotEmpty()) {
                        item(key = "pinned_header") {
                            SectionHeader("Pinned Folders")
                        }
                        item(key = "pinned_row") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(pinnedFolders, key = { it.id }) { pinned ->
                                    PinnedFolderChip(
                                        folder = pinned,
                                        onClick = { onPinnedFolderClick(pinned) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (visibleFiles.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 64.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("No videos in this folder", color = TextMuted)
                            }
                        }
                    } else {
                        item(key = "files_header") {
                            Spacer(Modifier.height(4.dp))
                        }
                        items(visibleFiles, key = { it.id }) { file ->
                            // Kick the lazy folder-thumbnail fetch as the row enters
                            // composition so the collage populates as the user
                            // scrolls. Idempotent — the VM only fires one network
                            // call per folder per session.
                            if (file.isFolder) {
                                LaunchedEffect(file.id) { onEnsureFolderThumbnails(file.id) }
                            }
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                FileItem(
                                    file = file,
                                    isPinned = pinnedFolders.any { it.id == file.id },
                                    isDownloaded = downloadedFileIds.contains(file.id),
                                    isDownloading = downloadingFileIds.contains(file.id),
                                    progressFraction = progressFractionFor(file, positions),
                                    folderChildThumbnails = if (file.isFolder) folderThumbnails[file.id] else null,
                                    onClick = { onFileClick(file, s.files) },
                                    onLongClick = { if (file.isFolder) onTogglePin(file) },
                                    onDownload = { onDownload(file) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    } // Box
}

// ── Search results ─────────────────────────────────────────────────────────────

@Composable
private fun SearchResultsContent(
    query: String,
    searchState: BrowserState?,
    recentSearches: List<String>,
    downloadedFileIds: Set<String>,
    downloadingFileIds: Set<String>,
    positions: Map<String, Long>,
    viewMode: String,
    onRecentClick: (String) -> Unit,
    onRecentRemove: (String) -> Unit,
    onClearRecents: () -> Unit,
    onVideoClick: (DriveFile) -> Unit,
    onDownload: (DriveFile) -> Unit,
) {
    when {
        query.isBlank() -> {
            // Two states share the empty-query view: a fresh user with no history
            // (centered hint) and a returning user who sees their recent queries
            // as chips for one-tap re-search.
            if (recentSearches.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Type to search your Drive videos", color = TextMuted)
                }
            } else {
                RecentSearchesPanel(
                    recents = recentSearches,
                    onPick = onRecentClick,
                    onRemove = onRecentRemove,
                    onClear = onClearRecents,
                )
            }
        }

        searchState == null || searchState is BrowserState.Loading -> {
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .wrapContentSize(Alignment.Center),
                color = AccentPrimary
            )
        }

        searchState is BrowserState.Error -> {
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

        searchState is BrowserState.Success -> {
            if (searchState.files.isEmpty()) {
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
                    item(key = "result_count", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "${searchState.files.size} result${if (searchState.files.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(items = searchState.files, key = { it.id }) { file ->
                        val subtitle = buildString {
                            if (file.isVideo) {
                                file.formattedDuration?.let { append(it) }
                                if (file.formattedSize.isNotEmpty()) {
                                    if (isNotEmpty()) append("  ·  ")
                                    append(file.formattedSize)
                                }
                            }
                        }
                        MediaGridCard(
                            title = file.name,
                            subtitle = subtitle,
                            progressFraction = if (file.isVideo) progressFractionFor(file, positions) else 0f,
                            qualityLabel = if (file.isVideo) file.qualityLabel else null,
                            onClick = { onVideoClick(file) },
                            thumbnail = {
                                if (file.isVideo) {
                                    CloudFileThumbnail(file = file, sizeHintPx = 400, modifier = Modifier.fillMaxSize())
                                } else {
                                    EmptyFolderThumbnail(modifier = Modifier.fillMaxSize())
                                }
                            },
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
                            "${searchState.files.size} result${if (searchState.files.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(searchState.files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            isPinned = false,
                            isDownloaded = downloadedFileIds.contains(file.id),
                            isDownloading = downloadingFileIds.contains(file.id),
                            progressFraction = progressFractionFor(file, positions),
                            // Search results don't fan out to a folder-listing
                            // network call per row — leaving collage data null
                            // falls back to the empty-folder thumbnail.
                            folderChildThumbnails = null,
                            onClick = { onVideoClick(file) },
                            onLongClick = {},
                            // Search results now expose the same per-row download
                            // affordance as the browse screen so users don't have
                            // to navigate to the parent folder first.
                            onDownload = { onDownload(file) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecentSearchesPanel(
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

// ── Small composables ──────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun WatchCard(entry: WatchEntry, onClick: () -> Unit) {
    val progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else 0f

    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Column {
            // 16:9 thumbnail with a play-icon overlay so the card still
            // reads as "video" even before Coil resolves the bitmap.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(AccentPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                val link = entry.thumbnailLink?.let { withDriveSize(it, 320) }
                if (link != null) {
                    AsyncImage(
                        model = link,
                        contentDescription = entry.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
                // Always render the play icon — it sits on top of the bitmap
                // when one resolves, and on the tinted box otherwise.
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (link != null) 0.85f else 0.6f),
                    modifier = Modifier.size(40.dp)
                )
            }
            // Watch-progress bar — flush against the thumbnail so it reads as
            // a single visual unit (matches VLC mobile's tile design).
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = AccentPrimary,
                trackColor = Color.White.copy(alpha = 0.18f),
            )
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.durationMs > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatDuration(entry.positionMs)} / ${formatDuration(entry.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedFolderChip(folder: PinnedFolder, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = AccentSecondary
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = CardSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.widthIn(max = 140.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: DriveFile,
    isPinned: Boolean,
    isDownloaded: Boolean,
    isDownloading: Boolean = false,
    progressFraction: Float = 0f,
    folderChildThumbnails: List<String>? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    var showPinDialog by remember { mutableStateOf(false) }

    if (showPinDialog && file.isFolder) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(if (isPinned) "Unpin folder?" else "Pin folder?") },
            text = {
                Text(
                    if (isPinned) "Remove \"${file.name}\" from pinned folders?"
                    else "Add \"${file.name}\" to pinned folders for quick access?"
                )
            },
            confirmButton = {
                TextButton(onClick = { showPinDialog = false; onLongClick() }) {
                    Text(if (isPinned) "Unpin" else "Pin", color = AccentPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = CardSurface
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .combinedClickable(
                enabled = file.isFolder || file.isVideo,
                onClick = onClick,
                onLongClick = { if (file.isFolder) showPinDialog = true }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Leading 96x54 thumbnail. Folders draw a 2x2 collage from the
        // resolved child thumbnails (null = not fetched yet → empty-state
        // tile so the row geometry is stable). Video files draw their own
        // Drive thumbnail with the quality chip + watch-progress overlay.
        Box(modifier = Modifier.size(width = 96.dp, height = 54.dp)) {
            when {
                file.isFolder -> {
                    if (folderChildThumbnails == null || folderChildThumbnails.isEmpty()) {
                        EmptyFolderThumbnail(modifier = Modifier.fillMaxSize())
                    } else {
                        CloudFolderCollageThumbnail(
                            childThumbnailUrls = folderChildThumbnails,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                file.isVideo -> {
                    CloudFileThumbnail(
                        file = file,
                        sizeHintPx = 220, // list mode uses the smaller variant
                        modifier = Modifier.fillMaxSize(),
                    )
                    file.qualityLabel?.let { label ->
                        QualityChip(
                            text = label,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp),
                        )
                    }
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
                else -> {
                    // Subtitles or unknown formats — keep a small icon tile so
                    // the row still has a visual anchor.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CardSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (file.isSrt) Icons.Default.Subtitles else Icons.Default.InsertDriveFile,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (file.isFolder || file.isVideo) TextPrimary else TextMuted,
                fontWeight = if (file.isFolder) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Subtitle line. For videos: duration · size · owner (matches the
            // local browser order). For folders / SRTs: original size · date
            // · owner combo so we still surface useful Drive metadata.
            val subtitle = buildString {
                if (file.isVideo) {
                    file.formattedDuration?.let { append(it) }
                }
                if (file.formattedSize.isNotEmpty()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(file.formattedSize)
                }
                if (!file.isVideo && file.modifiedTime != null) {
                    if (isNotEmpty()) append("  ·  ")
                    append(file.modifiedTime.take(10))
                }
                if (file.owners?.isNotEmpty() == true) {
                    if (isNotEmpty()) append("  ·  ")
                    append(file.owners.first().displayName ?: file.owners.first().emailAddress)
                }
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isPinned) {
            Icon(
                Icons.Default.PushPin,
                contentDescription = "Pinned",
                tint = AccentPrimary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }

        if (file.isVideo && onDownload != null) {
            when {
                isDownloaded -> {
                    Icon(
                        Icons.Default.DownloadDone,
                        contentDescription = "Downloaded",
                        tint = AccentPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                isDownloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AccentSecondary,
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    IconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = "Download",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
        }

        if (file.isFolder) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Shared per-row helpers ────────────────────────────────────────────────────

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
 * Watched-fraction (0f..1f) for a Drive file. Falls back to 0 when no
 * position is saved, the saved position is below the 5s "started watching"
 * threshold, or the file has no `videoMediaMetadata.durationMillis`.
 */
internal fun progressFractionFor(file: DriveFile, positions: Map<String, Long>): Float {
    val saved = positions[file.id] ?: return 0f
    if (saved <= 5_000L) return 0f
    val dur = file.durationMs ?: return 0f
    if (dur <= 0L) return 0f
    return (saved.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
}

// ── Account dropdown (unchanged) ──────────────────────────────────────────────

@Composable
private fun AccountDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    currentEmail: String,
    currentDisplayName: String?,
    savedAccounts: List<SavedAccount>,
    onSwitchAccount: (SavedAccount) -> Unit,
    onAddAccount: () -> Unit,
    onLogout: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(280.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentPrimary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    (currentDisplayName?.firstOrNull() ?: currentEmail.firstOrNull())
                        ?.uppercaseChar()?.toString() ?: "?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        currentDisplayName ?: currentEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = AccentPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    currentEmail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val otherAccounts = savedAccounts.filter { it.email != currentEmail }
        if (otherAccounts.isNotEmpty()) {
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                otherAccounts.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AccentSecondary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (account.displayName?.firstOrNull() ?: account.email.firstOrNull())
                                            ?.uppercaseChar()?.toString() ?: "?",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        account.displayName ?: account.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        account.email,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        onClick = { onSwitchAccount(account) }
                    )
                }
            }
        }

        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = TextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text("Add account", color = TextPrimary)
                }
            },
            onClick = onAddAccount
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Logout, contentDescription = null, tint = ColorError)
                    Spacer(Modifier.width(12.dp))
                    Text("Logout", color = ColorError)
                }
            },
            onClick = onLogout
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
