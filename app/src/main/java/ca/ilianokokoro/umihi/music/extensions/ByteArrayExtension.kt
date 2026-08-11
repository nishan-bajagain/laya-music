package ca.ilianokokoro.umihi.music.extensions

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import ca.ilianokokoro.umihi.music.core.Constants
import java.io.ByteArrayOutputStream

/**
 * Decodes [bytes] once with a power-of-two [BitmapFactory.Options.inSampleSize]
 * so the sampled result stays close to [maxDimension] on its longest side
 * without ever allocating a full-resolution bitmap. This matters for YouTube
 * thumbnails, which can be `maxresdefault`-sized (~1920×1080 ≈ 8 MB as
 * ARGB_8888) and are decoded on hot paths (one per song change during
 * playback, one per finished download).
 *
 * The returned bitmap's longest side is at most ~2× [maxDimension]; callers
 * that need an exact bound should scale it down via [cappedTo].
 */
internal fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
    // Pass 1: read the dimensions only — no pixel allocation.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var inSampleSize = 1
    while (
        bounds.outWidth / (inSampleSize * 2) > maxDimension ||
        bounds.outHeight / (inSampleSize * 2) > maxDimension
    ) {
        inSampleSize *= 2
    }

    // Pass 2: the only real decode of this artwork.
    val options = BitmapFactory.Options().apply { inSampleSize = inSampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

fun ByteArray.cappedTo(maxSize: Int = Constants.Ui.WEAROS_MAX_IMAGE_SIZE): ByteArray? {
    val sampled = decodeSampledBitmap(this, maxSize) ?: return null
    val capped = if (sampled.width <= maxSize && sampled.height <= maxSize) {
        sampled
    } else {
        val scale = maxSize.toFloat() / maxOf(sampled.width, sampled.height)
        val scaled = sampled.scale((sampled.width * scale).toInt(), (sampled.height * scale).toInt())
        // The sampled bitmap is superseded by the smaller scaled copy — release it eagerly.
        if (!sampled.isRecycled) {
            sampled.recycle()
        }
        scaled
    }
    val stream = ByteArrayOutputStream()
    capped.compress(Bitmap.CompressFormat.JPEG, 90, stream)
    if (!capped.isRecycled) {
        capped.recycle()
    }
    return stream.toByteArray()
}
