package ca.ilianokokoro.umihi.music.core.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import ca.ilianokokoro.umihi.music.core.Constants
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
import java.io.ByteArrayOutputStream
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


    suspend fun fetchArtworkBytes(url: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val bytes = UmihiHttpClient.client
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

                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext null

                ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    stream.toByteArray().cappedTo()
                }
            } catch (e: Exception) {
                LogHelper.printe("Failed to fetch artwork: ${e.message}", exception = e)
                null
            }
        }
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
