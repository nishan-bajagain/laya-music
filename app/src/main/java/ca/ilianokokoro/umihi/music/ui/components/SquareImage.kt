package ca.ilianokokoro.umihi.music.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.ilianokokoro.umihi.music.core.ImageErrorLog
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Square thumbnail with layered failure recovery.
 *
 * Pass the local cached copy via [localPath], the API art URL via [remoteUrl]
 * and an optional universal fallback via [fallbackUrl] (for songs this is
 * `https://i.ytimg.com/vi/{id}/hqdefault.jpg`).
 *
 * Recovery order:
 *  1. Coil tries each candidate (local file → API URL → i.ytimg.com fallback).
 *  2. If Coil exhausts every candidate (which includes *decode* failures — the
 *     ImageDecoder on some OEM/Android-16 devices rejects images Coil fetched
 *     fine), the bytes are re-fetched with the app's own OkHttp client and
 *     decoded with plain [BitmapFactory], bypassing Coil's decoder entirely.
 *  3. If even that fails, a neutral music-note placeholder is shown. A failed
 *     poster is never a blank tile, but no text is drawn over artwork.
 */
@Composable
fun SquareImage(
    modifier: Modifier = Modifier,
    localPath: String? = null,
    remoteUrl: String,
    fallbackUrl: String = "",
    contentDescription: String? = "Album artwork",
    cornerRadius: Dp = 12.dp,
    /**
     * Bounded decode size in pixels. 256 is plenty for 48–150dp list tiles
     * (≈180–450px @3x); the fullscreen player art passes ~1024 so it stays
     * sharp instead of looking pixelated.
     */
    requestSize: Int = 256,
) {
    val context = LocalContext.current

    // Ordered candidates: local cached file → API remote URL → i.ytimg.com
    // fallback. Each URL is sanitized (trims whitespace and fixes protocol-
    // relative "//host/..." values the YT Music API sometimes returns, which
    // Coil cannot fetch as-is) and duplicates are removed so a request is
    // never repeated twice.
    val candidates = remember(localPath, remoteUrl, fallbackUrl) {
        listOfNotNull(
            localPath?.takeIf { File(it).exists() },
            UmihiHelper.sanitizeImageUrl(remoteUrl),
            UmihiHelper.sanitizeImageUrl(fallbackUrl),
        ).distinct()
    }
    var attempt by remember(localPath, remoteUrl, fallbackUrl) { mutableIntStateOf(0) }
    var coilExhausted by remember(localPath, remoteUrl, fallbackUrl) { mutableStateOf(false) }

    // Manual-decode state, used only after Coil has failed every candidate.
    var manualBitmap by remember(localPath, remoteUrl, fallbackUrl) { mutableStateOf<Bitmap?>(null) }
    var manualFailed by remember(localPath, remoteUrl, fallbackUrl) { mutableStateOf(false) }

    // Cache the ImageRequest so it is not rebuilt on every recomposition.
    // Crossfade is intentionally disabled here: this component is used in
    // scrolling lists and rapid track changes, where animated transitions
    // keep extra bitmaps and invalidations alive.
    val imageRequest = remember(candidates, attempt, requestSize) {
        ImageRequest.Builder(context)
            .data(candidates.getOrNull(attempt))
            // Bounded decode: list thumbnails are rendered between 48–150dp and
            // never need a full-size remote album image per visible row.
            .size(requestSize, requestSize)
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

        if (manualBitmap != null) {
            // Last-resort success: plain BitmapFactory decode, Coil bypassed.
            Image(
                bitmap = manualBitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (!coilExhausted) {
            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = {
                    // A poster rendered (possibly via a fallback candidate) —
                    // clear any stale diagnostic error so the debug banner
                    // reflects the current state, not an earlier candidate
                    // failure that the fallback already recovered from.
                    ImageErrorLog.clear()
                },
                onError = {
                    if (attempt < candidates.lastIndex) {
                        // Local file failed → try the remote URL → then the
                        // i.ytimg.com fallback.
                        attempt++
                    } else {
                        coilExhausted = true
                    }
                }
            )
        }

        // Coil failed every candidate. Try one last time with the app's own
        // OkHttp client + plain BitmapFactory: this still succeeds when Coil's
        // Android decoder (ImageDecoder) is the broken link on a given device.
        if (coilExhausted && !manualFailed && manualBitmap == null) {
            LaunchedEffect(candidates, requestSize) {
                manualBitmap = withContext(Dispatchers.IO) {
                    fetchFirstDecodableBitmap(candidates, requestSize)
                }
                if (manualBitmap == null) {
                    manualFailed = true
                }
            }
        }
    }
}

/**
 * Downloads the first candidate that both (a) succeeds over the network and
 * (b) decodes with plain [BitmapFactory]. Returns null only if every candidate
 * fails on the network or is undecodable — the caller then shows the
 * placeholder. Errors are funneled through [ImageErrorLog] so the debug banner
 * always carries the real reason.
 */
private suspend fun fetchFirstDecodableBitmap(
    candidates: List<String>,
    requestSize: Int
): Bitmap? {
    for (candidate in candidates) {
        if (candidate.isBlank()) continue
        try {
            val request = Request.Builder()
                .url(candidate)
                .get()
                .build()
            val bytes = UmihiHttpClient.imageClient
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        ImageErrorLog.record(
                            "HTTP ${response.code} ${response.message} for $candidate"
                        )
                        return@use null
                    }
                    response.body?.bytes()
                } ?: continue

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue

            val sampleSize = UmihiHelper.calculateInSampleSize(
                bounds,
                reqWidth = requestSize,
                reqHeight = requestSize
            )
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bitmap != null) {
                ImageErrorLog.clear()
                return bitmap
            }
        } catch (e: Exception) {
            ImageErrorLog.record(
                "${e.javaClass.simpleName}: ${e.message ?: "no message"} for $candidate"
            )
        }
    }
    return null
}
