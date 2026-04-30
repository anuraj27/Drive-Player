package com.driveplayer.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.driveplayer.ui.theme.AccentPrimary
import com.driveplayer.ui.theme.CardSurface
import com.driveplayer.ui.theme.TextMuted
import com.driveplayer.ui.theme.TextPrimary

/**
 * Single tile composable used by both browse screens when the user picks the
 * grid layout. Hosts a 16:9 thumbnail (provided by the caller as a slot
 * composable so the same card works for local Uris, cloud thumbnailLink URLs,
 * and folder collages), an optional quality chip + watch-progress overlay,
 * and a two-line title/subtitle block underneath.
 *
 * Per the user's feedback in the plan, **duration is shown in the subtitle
 * line, not as a chip** — only the quality label sits over the thumbnail.
 * The progress bar is the only thumbnail overlay that changes per-frame, so
 * it lives at the bottom edge as a 2dp line, exactly like the list rows.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridCard(
    title: String,
    subtitle: String,
    progressFraction: Float,
    qualityLabel: String?,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    thumbnail: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            thumbnail()
            if (qualityLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        qualityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = AccentPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
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

        Spacer(Modifier.size(8.dp))

        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.size(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
