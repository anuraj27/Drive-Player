package com.driveplayer.ui.downloads

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driveplayer.player.DownloadEntry
import com.driveplayer.player.DownloadStatus
import com.driveplayer.ui.theme.*

@Composable
fun DownloadsScreen(
    onPlayDownload: (Uri, String) -> Unit,
    vm: DownloadsViewModel = viewModel(),
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = AccentPrimary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Downloads",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${downloads.count { it.entry.status == DownloadStatus.COMPLETED }} completed",
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
        }

        HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))

        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.DownloadForOffline,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(72.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("No downloads yet", color = TextMuted, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the download icon on any video\nin your Drive to save it offline.",
                    color = TextMuted.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val queuedItems = downloads.filter { it.entry.status == DownloadStatus.QUEUED }
                items(downloads, key = { it.entry.fileId }) { dp ->
                    val queuePosition = if (dp.entry.status == DownloadStatus.QUEUED)
                        queuedItems.indexOfFirst { it.entry.fileId == dp.entry.fileId } + 1
                    else null
                    DownloadItem(
                        dp = dp,
                        queuePosition = queuePosition,
                        canRetry = dp.entry.accessToken != null,
                        onPlay = {
                            val uri = vm.getPlayUri(dp.entry)
                            if (uri != null) onPlayDownload(uri, dp.entry.fileId)
                        },
                        onCancel = { vm.cancel(dp.entry) },
                        onRetry = { vm.retry(dp.entry) },
                        onDelete = { vm.delete(dp.entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItem(
    dp: DownloadProgress,
    queuePosition: Int?,
    canRetry: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete download?") },
            text = { Text("\"${dp.entry.title}\" will be removed from local storage.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete", color = ColorError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
            containerColor = CardSurface
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardSurface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // File icon with circular progress
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AccentPrimary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                when (dp.entry.status) {
                    DownloadStatus.COMPLETED -> {
                        Icon(
                            Icons.Default.VideoFile,
                            contentDescription = null,
                            tint = AccentPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DownloadStatus.FAILED -> {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = ColorError,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DownloadStatus.CANCELLED -> {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    DownloadStatus.QUEUED -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = AccentSecondary,
                            strokeWidth = 2.dp
                        )
                    }
                    DownloadStatus.RUNNING -> {
                        val animatedProgress by animateFloatAsState(
                            targetValue = dp.fraction,
                            animationSpec = tween(durationMillis = 300),
                            label = "progress"
                        )
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(24.dp),
                                color = AccentSecondary,
                                strokeWidth = 2.dp,
                                strokeCap = StrokeCap.Round
                            )
                            if (animatedProgress > 0.1f) {
                                Text(
                                    text = "${(animatedProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Title + status
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dp.entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                StatusChip(dp = dp, queuePosition = queuePosition)
            }

            Spacer(Modifier.width(8.dp))

            // Actions
            when (dp.entry.status) {
                DownloadStatus.COMPLETED -> {
                    IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = AccentPrimary, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = ColorError, modifier = Modifier.size(20.dp))
                    }
                }
                DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
                DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                    if (canRetry) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = AccentSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = TextMuted, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Progress bar for running downloads
        if (dp.entry.status == DownloadStatus.RUNNING) {
            Spacer(Modifier.height(8.dp))
            Column {
                if (dp.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { dp.fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentPrimary,
                        trackColor = TextMuted.copy(alpha = 0.2f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${(dp.fraction * 100).toInt()}% • ${dp.formattedProgress}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentPrimary,
                        trackColor = TextMuted.copy(alpha = 0.2f)
                    )
                    if (dp.bytesDownloaded > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatBytes(dp.bytesDownloaded)} downloaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(dp: DownloadProgress, queuePosition: Int?) {
    val (label, color) = when (dp.entry.status) {
        DownloadStatus.QUEUED -> (if (queuePosition != null && queuePosition > 1) "In queue  ·  #$queuePosition" else "Waiting to start") to TextMuted
        DownloadStatus.RUNNING -> when {
            dp.totalBytes > 0 -> "${(dp.fraction * 100).toInt()}%  ·  ${dp.formattedProgress}" to AccentSecondary
            dp.bytesDownloaded > 0 -> "${formatBytes(dp.bytesDownloaded)} downloaded" to AccentSecondary
            else -> "Connecting…" to AccentSecondary
        }
        DownloadStatus.COMPLETED -> "Downloaded" to AccentPrimary
        DownloadStatus.FAILED    -> if (dp.totalBytes > 0)
                                        "Failed  ·  ${dp.formattedProgress} received" to ColorError
                                    else "Failed" to ColorError
        DownloadStatus.CANCELLED -> if (dp.totalBytes > 0)
                                        "Cancelled  ·  ${dp.formattedProgress}" to TextMuted
                                    else "Cancelled" to TextMuted
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = if (dp.entry.status == DownloadStatus.COMPLETED) FontWeight.SemiBold else FontWeight.Normal
    )
}
