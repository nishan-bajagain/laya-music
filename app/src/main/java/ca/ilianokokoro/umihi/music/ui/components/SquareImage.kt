package ca.ilianokokoro.umihi.music.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

@Composable
fun SquareImage(
    modifier: Modifier = Modifier,
    uri: String,
    contentDescription: String? = null,
    cornerRadius: Dp = 12.dp,
) {
    // Cache the ImageRequest so it is not rebuilt on every recomposition.
    // Crossfade is intentionally disabled here: this component is used in
    // scrolling lists and rapid track changes, where animated transitions
    // keep extra bitmaps and invalidations alive.
    val context = LocalContext.current
    val imageRequest = remember(uri) {
        ImageRequest.Builder(context)
            .data(uri)
            // List thumbnails are rendered between 48–150dp. A bounded decode
            // prevents a full-size remote album image from being allocated for
            // every visible row while scrolling.
            .size(256, 256)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius)),
        error = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}
