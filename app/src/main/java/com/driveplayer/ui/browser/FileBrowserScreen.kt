package com.driveplayer.ui.browser

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.data.model.DriveFile
import com.driveplayer.data.remote.DriveRepository
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
    vm: FileBrowserViewModel = viewModel(key = accountEmail, factory = FileBrowserViewModel.Factory(repo))
) {
    val state       by vm.state.collectAsStateWithLifecycle()
    val folderStack by vm.folderStack.collectAsStateWithLifecycle()
    val tabMode     by vm.tabMode.collectAsStateWithLifecycle()
    var showAccountMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = folderStack.size > 1) { vm.goBack() }

    Scaffold(
        topBar = {
            Column {
                // Tab row + profile icon on the same line
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DarkBackground),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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

                    // Profile icon — anchors the account dropdown
                    Box(modifier = Modifier.padding(horizontal = 4.dp)) {
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

                // Folder breadcrumb bar
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
        },
        containerColor = DarkBackground
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is BrowserState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = AccentPrimary
                    )
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
                        Button(onClick = { vm.refresh() }, colors = ButtonDefaults.buttonColors(containerColor = AccentPrimary)) {
                            Text("Retry")
                        }
                    }
                }

                is BrowserState.Success -> {
                    if (s.files.isEmpty()) {
                        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextMuted, modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("This folder is empty", color = TextMuted)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(s.files, key = { it.id }) { file ->
                                FileItem(
                                    file = file,
                                    onClick = {
                                        if (file.isFolder) vm.openFolder(file)
                                        else if (file.isVideo) onVideoClick(file, s.files)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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
        // Active account header (not a button — just shows who's in)
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

        // Other linked accounts (scrollable if the list is long)
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

@Composable
private fun FileItem(file: DriveFile, onClick: () -> Unit) {
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
            .clickable(enabled = file.isFolder || file.isVideo, onClick = onClick)
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
                            append("Owner: ${file.owners.first().displayName ?: file.owners.first().emailAddress}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
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

@Composable
private fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
