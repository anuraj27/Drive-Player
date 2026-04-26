package com.driveplayer.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.driveplayer.player.PinnedFolder
import com.driveplayer.player.WatchEntry
import com.driveplayer.ui.cloud.SavedAccount
import com.driveplayer.ui.theme.*

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
    vm: FileBrowserViewModel = viewModel(key = accountEmail, factory = FileBrowserViewModel.Factory(repo, accessToken))
) {
    val state           by vm.state.collectAsStateWithLifecycle()
    val folderStack     by vm.folderStack.collectAsStateWithLifecycle()
    val tabMode         by vm.tabMode.collectAsStateWithLifecycle()
    val isSearchActive    by vm.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery       by vm.searchQuery.collectAsStateWithLifecycle()
    val searchState       by vm.searchState.collectAsStateWithLifecycle()
    val pinnedFolders     by vm.pinnedFolders.collectAsStateWithLifecycle()
    val recentlyWatched   by vm.recentlyWatched.collectAsStateWithLifecycle()
    val downloadedFileIds by vm.downloadedFileIds.collectAsStateWithLifecycle()
    val downloadingFileIds by vm.downloadingFileIds.collectAsStateWithLifecycle()

    var showAccountMenu by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    // Navigate to a recently-watched file — we need sibling files from the current list
    // so we just pass an empty siblings list; PlayerController will still play the file.
    fun openWatchEntry(entry: WatchEntry) {
        val synthetic = DriveFile(
            id = entry.fileId,
            name = entry.title,
            mimeType = entry.mimeType,
        )
        onVideoClick(synthetic, emptyList())
    }

    val canGoBack = isSearchActive || folderStack.size > 1
    BackHandler(enabled = canGoBack) { vm.goBack() }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            Column {
                // ── Tab row + profile icon ─────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkBackground),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isSearchActive) {
                        TabRow(
                            modifier = Modifier.weight(1f),
                            selectedTabIndex = if (tabMode == TabMode.MY_DRIVE) 0 else 1,
                            containerColor = DarkBackground,
                            contentColor = AccentPrimary
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
                    } else {
                        Spacer(Modifier.width(8.dp))
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
                        Spacer(Modifier.width(8.dp))
                    }

                    if (!isSearchActive) {
                        // Search icon
                        IconButton(onClick = { vm.activateSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = TextSecondary)
                        }
                        // Profile icon
                        Box(modifier = Modifier.padding(end = 4.dp)) {
                            IconButton(onClick = { showAccountMenu = true }) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AccentPrimary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        (displayName?.firstOrNull() ?: accountEmail.firstOrNull())
                                            ?.uppercaseChar()?.toString() ?: "?",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
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
                                onLogout = { showAccountMenu = false; onLogout() }
                            )
                        }
                    }
                }

                // ── Folder breadcrumb (only when not in search mode) ───────
                if (!isSearchActive) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    vm.currentFolder.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (folderStack.size > 1) {
                                    Text(
                                        folderStack.dropLast(1).joinToString(" › ") { it.name },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (folderStack.size > 1) {
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
                            IconButton(onClick = { vm.refresh() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
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
                    downloadedFileIds = downloadedFileIds,
                    downloadingFileIds = downloadingFileIds,
                    onVideoClick = { file -> onVideoClick(file, emptyList()) }
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

                // ── File list ──────────────────────────────────────────────
                if (s.files.isEmpty()) {
                    item(key = "empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 64.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("This folder is empty", color = TextMuted)
                        }
                    }
                } else {
                    item(key = "files_header") {
                        Spacer(Modifier.height(4.dp))
                    }
                    items(s.files, key = { it.id }) { file ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                            FileItem(
                                file = file,
                                isPinned = pinnedFolders.any { it.id == file.id },
                                isDownloaded = downloadedFileIds.contains(file.id),
                                isDownloading = downloadingFileIds.contains(file.id),
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
    } // Box
}

// ── Search results ─────────────────────────────────────────────────────────────

@Composable
private fun SearchResultsContent(
    query: String,
    searchState: BrowserState?,
    downloadedFileIds: Set<String>,
    downloadingFileIds: Set<String>,
    onVideoClick: (DriveFile) -> Unit,
) {
    when {
        query.isBlank() -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Type to search your Drive videos", color = TextMuted)
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
                            onClick = { onVideoClick(file) },
                            onLongClick = {},
                        )
                    }
                }
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
    val remaining = entry.durationMs - entry.positionMs

    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurface)
    ) {
        Column {
            // Thumbnail placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(AccentPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayCircleOutline,
                    contentDescription = null,
                    tint = AccentPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(40.dp)
                )
            }
            // Progress bar
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = AccentPrimary,
                trackColor = TextMuted.copy(alpha = 0.3f)
            )
            // Title and time remaining
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

    val (icon, iconColor) = when {
        file.isFolder -> Icons.Default.Folder to AccentSecondary
        file.isVideo  -> Icons.Default.VideoFile to AccentPrimary
        file.isSrt    -> Icons.Default.Subtitles to TextMuted
        else          -> Icons.Default.InsertDriveFile to TextMuted
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (file.isFolder || file.isVideo) TextPrimary else TextMuted,
                fontWeight = if (file.isFolder) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (file.formattedSize.isNotEmpty() || file.modifiedTime != null || file.owners?.isNotEmpty() == true) {
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (file.formattedSize.isNotEmpty()) append(file.formattedSize)
                        if (file.modifiedTime != null) {
                            if (isNotEmpty()) append("  ·  ")
                            append(file.modifiedTime.take(10))
                        }
                        if (file.owners?.isNotEmpty() == true) {
                            if (isNotEmpty()) append("  ·  ")
                            append(file.owners.first().displayName ?: file.owners.first().emailAddress)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
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
