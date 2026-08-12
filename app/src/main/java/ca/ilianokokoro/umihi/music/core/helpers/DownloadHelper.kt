package ca.ilianokokoro.umihi.music.core.helpers

import android.content.Context
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.ImageErrorLog
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

object DownloadHelper {

    /**
     * Download a thumbnail image. Files land in [customBasePath] when provided,
     * otherwise in the app's internal files directory. If the primary [imageUrl]
     * fails (unreachable CDN host, HTTP error, protocol-relative URL), [fallbackUrl]
     * — usually the universal i.ytimg.com thumbnail — is tried before giving up,
     * so offline thumbnails keep working even when the API art host is blocked.
     */
    suspend fun downloadImage(
        context: Context,
        imageUrl: String,
        id: String,
        customBasePath: String? = null,
        fallbackUrl: String = ""
    ): File? {
        return withContext(Dispatchers.IO) {
            val candidates = listOfNotNull(
                UmihiHelper.sanitizeImageUrl(imageUrl),
                UmihiHelper.sanitizeImageUrl(fallbackUrl),
            ).distinct()

            if (candidates.isEmpty()) {
                printe(tag = "PlaylistDownloadWorker", message = "Thumbnail URL is empty for $id")
                return@withContext null
            }

            val imageDir = UmihiHelper.getDownloadDirectory(
                context,
                Constants.Downloads.THUMBNAILS_FOLDER,
                customBasePath
            )
            val imageFile = File(imageDir, "$id.jpg")

            if (imageFile.exists()) {
                printd("Song Image $id was already downloaded")
                return@withContext imageFile
            }

            for (candidate in candidates) {
                try {
                    val tempFile = File(imageDir, "$id.jpg.part")
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }

                    val request = Request.Builder()
                        .url(candidate)
                        .get()
                        .build()

                    UmihiHttpClient.imageClient
                        .newCall(request)
                        .execute()
                        .use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("HTTP ${response.code}: ${response.message}")
                            }

                            val body = response.body ?: throw IOException("Empty image response body")

                            body.byteStream().use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }

                    if (!tempFile.renameTo(imageFile)) {
                        throw IOException("Failed to rename thumbnail temp file")
                    }

                    ImageErrorLog.clear()
                    return@withContext imageFile
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    printe(
                        tag = "PlaylistDownloadWorker",
                        message = "Error Downloading Thumbnail from $candidate",
                        exception = e
                    )
                }
            }
            null
        }
    }

    /**
     * Download audio for [song]. Files land in [customBasePath] when provided,
     * otherwise in the app's internal files directory.
     */
    suspend fun downloadAudio(
        context: Context,
        song: Song,
        retries: Int = Constants.YoutubeApi.RETRY_COUNT,
        customBasePath: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val audioDir = UmihiHelper.getDownloadDirectory(
            context,
            Constants.Downloads.AUDIO_FILES_FOLDER,
            customBasePath
        )

        val outputFile = File(audioDir, "${song.youtubeId}.webm")
        val tempFile = File(audioDir, "${song.youtubeId}.webm.part")

        if (outputFile.exists()) {
            printd("Song file ${song.title} was already downloaded")
            return@withContext outputFile.absolutePath
        }

        // Storage space check — require at least 10 MB free before starting.
        // This prevents cryptic IOExceptions when the device is nearly full.
        val stat = android.os.StatFs(audioDir.absolutePath)
        val availableBytes = stat.availableBytes
        if (availableBytes < 10L * 1024 * 1024) {
            throw IOException("Insufficient storage space: only ${availableBytes / 1024}KB free")
        }

        val url = YoutubeDataExtractor.getSongPlayerUrl(context, song)

        var lastException: Exception? = null

        repeat(retries) { attempt ->
            try {
                // Resume support: if a .part file exists from a previous failed
                // attempt, append to it using HTTP Range instead of restarting from zero.
                val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(url)

                if (existingBytes > 0) {
                    // Request the remaining bytes from where we left off
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                    printd("Resuming download for ${song.youtubeId} from byte $existingBytes")
                } else {
                    requestBuilder.header("Range", "bytes=0-")
                }

                val request = requestBuilder.build()

                UmihiHttpClient.downloadClient
                    .newCall(request)
                    .execute()
                    .use { response ->
                        // 200 = full content (server ignored Range), 206 = partial content (resume OK)
                        if (!response.isSuccessful && response.code != 206) {
                            throw IOException("Failed to download audio: ${response.code}")
                        }

                        // If server returned 200 (full content) but we have a .part file,
                        // the server doesn't support resume — restart from scratch.
                        val appendMode = response.code == 206 && existingBytes > 0

                        val body = response.body
                            ?: throw IOException("Empty audio response body")

                        body.byteStream().use { input ->
                            if (appendMode) {
                                // Append to existing .part file
                                java.io.FileOutputStream(tempFile, true).use { output ->
                                    input.copyTo(output)
                                }
                            } else {
                                // Fresh download — overwrite .part file
                                java.io.FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }

                if (outputFile.exists()) {
                    outputFile.delete()
                }

                if (!tempFile.renameTo(outputFile)) {
                    throw IOException("Failed to rename temp audio file")
                }

                return@withContext outputFile.absolutePath
            } catch (e: CancellationException) {
                // On cancellation, keep the .part file so a retry can resume.
                throw e
            } catch (e: Exception) {
                // Keep the .part file for resume on next attempt.
                // Only delete it if the file is 0 bytes (nothing was downloaded).
                if (tempFile.exists() && tempFile.length() == 0L) {
                    tempFile.delete()
                }
                lastException = e

                if (attempt == retries - 1) {
                    // Final attempt failed — clean up the .part file
                    tempFile.delete()
                    throw e
                }
            }
        }

        printe(
            message = "Download failed for ${song.youtubeId}: ${lastException?.message}",
            exception = lastException
        )

        tempFile.delete()
        outputFile.delete()
        null
    }
}
