package com.driveplayer.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.driveplayer.data.local.LocalVideo
import com.driveplayer.data.local.VideoFolder
import com.driveplayer.data.model.DriveFile
import com.driveplayer.ui.theme.AccentPrimary
import com.driveplayer.ui.theme.AccentSecondary
import com.driveplayer.ui.theme.CardSurface

/**
 * One-stop thumbnail composables used by every browse / list / card UI.
 *
 * Why a single helper module:
 *  - Coil's `AsyncImage` works the same whether the source is a Drive URL,
 *    a `content://` MediaStore Uri, or a local file path. Picking the right
 *    `data` argument is the only divergence — we hide that here.
 *  - Folder thumbnails on a video app should preview the folder's contents,
 *    not show a generic icon. We render a 2x2 collage of up to 4 child thumbs
 *    so users can recognise content at a glance.
 *  - Loading & error states should look the same everywhere — a tinted
 *    `CardSurface` box with a centred icon, never a blank rectangle.
 */

private val ThumbCorner = RoundedCornerShape(10.dp)

/**
 * Single-image thumbnail for a [LocalVideo]. Coil fetches the MediaStore
 * thumbnail off-thread; on first paint we show a tinted placeholder with a
 * `VideoFile` icon so list rows don't pop in.
 */
@Composable
fun LocalVideoThumbnail(
    video: LocalVideo,
    modifier: Modifier = Modifier,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = modifier
            .clip(ThumbCorner)
            .background(CardSurface)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(video.uri)
                .crossfade(true)
                .build(),
            contentDescription = video.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // Soft fallback icon visible while the bitmap decodes.
        Icon(
            imageVector = Icons.Default.VideoFile,
            contentDescription = null,
            tint = AccentPrimary.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp),
        )
    }
}

/**
 * Single-image thumbnail for a Drive file. We append `=s400` to bias the
 * server toward a higher-res variant; Drive returns ~220px by default which
 * looks soft on grid cards. Falls back to a tinted icon when [DriveFile]
 * has no `thumbnailLink` (rare for videos, common for folders / SRTs).
 */
@Composable
fun CloudFileThumbnail(
    file: DriveFile,
    modifier: Modifier = Modifier,
    sizeHintPx: Int = 400,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val link = file.thumbnailLink?.let { withDriveSize(it, sizeHintPx) }
    Box(
        modifier = modifier
            .clip(ThumbCorner)
            .background(CardSurface)
    ) {
        if (link != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(link)
                    .crossfade(true)
                    .build(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // Always render the icon — when AsyncImage paints the bitmap it
        // sits on top, and otherwise the icon is the visible fallback.
        val icon = if (file.isFolder) Icons.Default.Folder else Icons.Default.VideoFile
        val tint = if (file.isFolder) AccentSecondary else AccentPrimary
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (link == null) 1f else 0.35f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(if (link == null) 36.dp else 32.dp),
        )
    }
}

/**
 * 2x2 collage thumbnail for a local [VideoFolder]. We pick the folder's
 * first 4 videos. With <4 children we still draw a 2x2 grid but reuse the
 * available thumbnails to fill the slots, so the card never has visible
 * blank cells. With 0 children we degrade to a centred folder icon.
 */
@Composable
fun FolderCollageThumbnail(
    folder: VideoFolder,
    modifier: Modifier = Modifier,
) {
    val sources = folder.videos.take(4).map { it.uri }
    CollageBox(modifier = modifier, sources = sources, itemKey = { it })
}

/**
 * Same collage shape, fed by an externally-resolved list of cloud thumbnail
 * URLs. Used by [com.driveplayer.ui.browser.FileBrowserScreen] for cloud
 * folders — children must be fetched on demand because Drive only ships a
 * folder's metadata, not its contents.
 */
@Composable
fun CloudFolderCollageThumbnail(
    childThumbnailUrls: List<String>,
    modifier: Modifier = Modifier,
) {
    CollageBox(modifier = modifier, sources = childThumbnailUrls.take(4), itemKey = { it })
}

/**
 * Empty-state collage drawn when we don't yet (or won't ever) have child
 * thumbnails to show. Visually matches the loaded collage so the card
 * doesn't reflow when data arrives.
 */
@Composable
fun EmptyFolderThumbnail(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(ThumbCorner)
            .background(CardSurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = AccentSecondary,
            modifier = Modifier.size(40.dp),
        )
    }
}

/**
 * Generic collage drawer used by both local and cloud variants. Always
 * renders a 2x2 grid even when fewer sources are available — when we have
 * fewer than 4 we cycle the list so every cell is populated, which looks
 * cleaner than visible blank tiles.
 *
 * `T : Any` is required because Coil's `ImageRequest.Builder.data(...)`
 * accepts `Any` (it dispatches by runtime type — Uri, String, File, Bitmap,
 * etc.). An unbounded generic would forbid the upcast.
 */
@Composable
private fun <T : Any> CollageBox(
    modifier: Modifier,
    sources: List<T>,
    @Suppress("UNUSED_PARAMETER") itemKey: (T) -> Any,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = modifier
            .clip(ThumbCorner)
            .background(CardSurface),
    ) {
        when {
            sources.isEmpty() -> {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = AccentSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                )
            }
            sources.size == 1 -> {
                // Single source looks better full-bleed than as one of four tiles.
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(sources[0])
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                // Tiny "stack" hint so users still recognise it as a folder.
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp),
                )
            }
            else -> {
                // 2x2 grid — cycle the list so 2 or 3 sources still fill all
                // four cells. The 1dp gap is the card surface bleeding through;
                // it gives the collage a clean "tiled" feel without an extra
                // border draw.
                val tiles = listOf(
                    sources.getOrNull(0) ?: sources[0],
                    sources.getOrNull(1) ?: sources[0],
                    sources.getOrNull(2) ?: sources[0],
                    sources.getOrNull(3) ?: sources.getOrNull(1) ?: sources[0],
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().height(0.dp).weight(1f)) {
                        CollageTile(ctx, tiles[0], Modifier.fillMaxHeight().width(0.dp).weight(1f))
                        Spacer(Modifier.width(1.dp))
                        CollageTile(ctx, tiles[1], Modifier.fillMaxHeight().width(0.dp).weight(1f))
                    }
                    Spacer(Modifier.height(1.dp))
                    Row(modifier = Modifier.fillMaxWidth().height(0.dp).weight(1f)) {
                        CollageTile(ctx, tiles[2], Modifier.fillMaxHeight().width(0.dp).weight(1f))
                        Spacer(Modifier.width(1.dp))
                        CollageTile(ctx, tiles[3], Modifier.fillMaxHeight().width(0.dp).weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageTile(ctx: android.content.Context, source: Any, modifier: Modifier) {
    Box(modifier = modifier.background(CardSurface)) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(source).crossfade(true).build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * Drive thumbnailLink size suffix — appending `=s<n>` (or `=w<n>-h<n>`)
 * asks the CDN for a resized variant. We use the simpler `=s` form which
 * fits-square; `ContentScale.Crop` does the final framing on the client.
 *
 * If the URL already ends with a size suffix (Drive sometimes adds one),
 * we replace it; otherwise we append.
 */
internal fun withDriveSize(url: String, sizePx: Int): String {
    val sIdx = url.lastIndexOf("=s")
    return if (sIdx > 0 && url.substring(sIdx + 2).all { it.isDigit() }) {
        url.substring(0, sIdx) + "=s$sizePx"
    } else {
        "$url=s$sizePx"
    }
}
