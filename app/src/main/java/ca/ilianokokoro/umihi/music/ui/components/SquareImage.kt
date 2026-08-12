package ca.ilianokokoro.umihi.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

/**
 * Square thumbnail with built-in local→remote fallback.
 *
 * Pass the local cached copy via [localPath] and the remote URL via [remoteUrl]
 * (instead of pre-resolving them with `?:` at the call site) so the fallback,
 * single retry and neutral placeholder live in exactly one place. If the local
 * file is missing/corrupt the request automatically retries against the remote
 * URL once; if that fails too, a music-note placeholder is shown instead of a
 * blank tile.
 */
@Composable
fun SquareImage(
    modifier: Modifier = Modifier,
    localPath: String? = null,
    remoteUrl: String,
    contentDescription: String? = null,
    cornerRadius: Dp = 12.dp,
) {
    val context = LocalContext.current
    var retryToken by remember(localPath, remoteUrl) { mutableIntStateOf(0) }
    var failed by remember(localPath, remoteUrl) { mutableStateOf(false) }

    // Prefer the local cached copy, but if it's missing/corrupt, fall back to
    // the remote URL instead of failing outright.
    val primaryUrl = localPath?.takeIf { File(it).exists() } ?: remoteUrl

    // Cache the ImageRequest so it is not rebuilt on every recomposition.
    // Crossfade is intentionally disabled here: this component is used in
    // scrolling lists and rapid track changes, where animated transitions
    // keep extra bitmaps and invalidations alive.
    val imageRequest = remember(primaryUrl, retryToken) {
        ImageRequest.Builder(context)
            .data(if (retryToken == 0) primaryUrl else remoteUrl)
            // List thumbnails are rendered between 48–150dp. A bounded decode
            // prevents a full-size remote album image from being allocated for
            // every visible row while scrolling.
            .size(256, 256)
            .build()
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        // Base layer so a failed/loading image never leaves a blank tile.
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        if (!failed) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = {
                    if (retryToken == 0 && primaryUrl != remoteUrl) {
                        // Local file failed — retry against the remote URL once.
                        retryToken = 1
                    } else {
                        failed = true
                    }
                }
            )
        }
    }
}
