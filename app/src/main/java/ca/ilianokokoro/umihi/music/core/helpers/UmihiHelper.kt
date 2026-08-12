package ca.ilianokokoro.umihi.music.core.helpers

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.ImageErrorLog
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.extensions.cappedTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.File
import java.nio.file.Paths
import java.util.Locale
import kotlin.random.Random


object UmihiHelper {

    /**
     * Returns the download directory, using [customBasePath] as the root when provided,
     * otherwise falling back to [Context.filesDir] (internal storage).
     */
    fun getDownloadDirectory(
        context: Context,
        directory: String? = null,
        customBasePath: String? = null
    ): File {
        val base = customBasePath?.let { File(it) } ?: context.filesDir
        val dir = File(
            base,
            if (directory == null)
                Constants.Downloads.DIRECTORY
            else
                Paths.get(Constants.Downloads.DIRECTORY, directory).toString()
        )
        dir.mkdirs()
        return dir
    }

    /**
     * Returns a list of (label, absolutePath) pairs representing writable storage locations.
     * Always includes internal storage first; SD cards follow if present and mounted.
     */
    fun getAvailableStorageOptions(context: Context): List<Pair<String, String>> {
        val options = mutableListOf<Pair<String, String>>()

        // Internal storage — always available
        options.add("Internal Storage" to context.filesDir.absolutePath)

        // Only show actual SD card directories (index >= 1).
        // Index 0 is the primary external/emulated storage — indistinguishable from internal
        // storage to the user and is therefore excluded to avoid confusion.
        context.getExternalFilesDirs(null).forEachIndexed { index, file ->
            if (index == 0) return@forEachIndexed  // skip primary "external" (emulated)
            if (file != null && file.exists() && file.canWrite()) {
                val state = Environment.getExternalStorageState(file)
                if (state == Environment.MEDIA_MOUNTED) {
                    options.add("SD Card" to file.absolutePath)
                }
            }
        }

        return options
    }


    /**
     * Normalizes an image URL before it is stored or fetched. The YT Music API
     * frequently returns protocol-relative URLs ("//lh3.googleusercontent.com/...")
     * which Coil/OkHttp cannot fetch (no scheme → "no fetcher found" error), while
     * the same asset served from a full "https://" URL loads fine. Trimming and
     * prefixing the scheme turns those broken URLs into fetchable ones. Returns
     * "" for blank/null/invalid input so callers can skip the candidate.
     */
    fun sanitizeImageUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var trimmed = url.trim()
        if (trimmed.equals("null", ignoreCase = true)) return ""
        if (trimmed.startsWith("//")) trimmed = "https:$trimmed"
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return ""
        return trimmed
    }

    /**
     * Normalizes a Google account-photo URL for display: sanitizes it (see
     * [sanitizeImageUrl]) and upgrades the `=sNN-c` size suffix the
     * account_menu API returns — usually `=s96-c`, a 96px thumbnail that looks
     * pixelated when shown at 140dp (~420px @3x) — to `=s256-c` so the served
     * image matches the avatar's on-screen size. Handles trailing variants
     * like `=s96-c-k-no`. Returns "" for unusable URLs.
     */
    fun normalizeGoogleAvatarUrl(url: String?): String {
        val sanitized = sanitizeImageUrl(url)
        if (sanitized.isBlank()) return ""
        return sanitized.replace(Regex("=s\\d+-c[^?]*"), "=s256-c")
    }

    /**
     * Fetches artwork bytes, trying [url] first and falling back to
     * [fallbackUrl] (e.g. the universal i.ytimg.com thumbnail) if the primary
     * CDN host is unreachable or rejects the request. The result is decoded
     * once, bounded via [cappedTo], so large thumbnails (e.g. ~1920×1080
     * maxresdefault) are never decoded at full resolution.
     */
    suspend fun fetchArtworkBytes(url: String, fallbackUrl: String = ""): ByteArray? {
        return withContext(Dispatchers.IO) {
            val candidates = listOfNotNull(
                sanitizeImageUrl(url),
                sanitizeImageUrl(fallbackUrl),
            ).distinct()

            for (candidate in candidates) {
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
                                throw IllegalStateException(
                                    "Failed to fetch artwork. HTTP ${response.code}: ${response.message}"
                                )
                            }

                            response.body?.bytes()
                                ?: throw IllegalStateException("Empty artwork response body")
                        }

                    // Single bounded decode: [cappedTo] samples the image down
                    // before decoding and re-encodes it once, so large thumbnails
                    // are never decoded at full resolution and no intermediate
                    // full-size bitmap is kept alive.
                    ImageErrorLog.clear()
                    return@withContext bytes.cappedTo()
                } catch (e: Exception) {
                    LogHelper.printe(
                        "Failed to fetch artwork from $candidate: ${e.message}",
                        exception = e
                    )
                }
            }
            null
        }
    }

    /**
     * Computes a power-of-two [BitmapFactory.Options.inSampleSize] that downsamples
     * an image with the given [BitmapFactory.Options.outWidth]/[outHeight] bounds so
     * the decoded bitmap stays close to [reqWidth] x [reqHeight] without ever
     * allocating a full-resolution intermediate.
     */
    fun calculateInSampleSize(
        bounds: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = bounds.outHeight
        val width = bounds.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // Largest power of two that keeps both dimensions above the request.
            while (
                (halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun Float.speedLabel(): String {
        val value = String.format(Locale.ROOT, "%.2f", this)
            .trimEnd('0')
            .trimEnd('.')

        return "${value}x"
    }

    fun Float.formatDecimal(): String {
        return if (this == this.toLong().toFloat()) {
            this.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    fun String?.isNullOrInvalidId(): Boolean =
        this.isNullOrBlank() || this.equals("null", ignoreCase = true)

    fun JsonElement.safeObject(): JsonObject? =
        try {
            this.jsonObject
        } catch (_: Exception) {
            null
        }

    fun JsonElement.safeArray(): JsonArray? =
        try {
            this.jsonArray
        } catch (_: Exception) {
            null
        }


    object Cpn {
        private const val CPN_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        private const val CPN_LENGTH = 16

        fun generate(): String = buildString {
            repeat(CPN_LENGTH) {
                append(
                    CPN_CHARS[Random.nextInt(
                        CPN_CHARS.length
                    )]
                )
            }
        }

    }
}
