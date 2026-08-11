package ca.ilianokokoro.umihi.music.core.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.core.managers.UpdateManager
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads the update APK into `cacheDir/updates/` using the same resumable
 * pattern as [ca.ilianokokoro.umihi.music.core.helpers.DownloadHelper.downloadAudio]:
 * a `.part` file that is appended to via HTTP `Range` and renamed on success.
 *
 * Enqueued as unique work ([Constants.Update.DOWNLOAD_WORK_NAME]) so tapping
 * "Update now" twice can never start two concurrent downloads.
 */
class AppUpdateDownloadWorker(
    private val appContext: Context,
    private val params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val version = params.inputData.getString(VERSION_KEY)
            ?: return Result.failure()
        val url = params.inputData.getString(URL_KEY)
            ?: return Result.failure()

        val updateDir = File(appContext.cacheDir, Constants.Update.UPDATE_CACHE_FOLDER)
        updateDir.mkdirs()

        val outputFile = File(updateDir, "laya-$version.apk")
        val tempFile = File(updateDir, "laya-$version.apk.part")

        return try {
            if (outputFile.exists()) {
                printd("Update APK $version was already downloaded")
                UpdateManager.onUpdateDownloaded(appContext, outputFile, version)
                return Result.success()
            }

            // Resume support: if a .part file exists from a previous failed
            // attempt, append to it using HTTP Range instead of restarting.
            val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

            val requestBuilder = Request.Builder().url(url)
            requestBuilder.header("Range", "bytes=$existingBytes-")

            UmihiHttpClient.downloadClient
                .newCall(requestBuilder.build())
                .execute()
                .use { response ->
                    // 200 = full content, 206 = partial content (resume OK)
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("Failed to download update: ${response.code}")
                    }

                    // If the server returned 200 while a .part file exists, the
                    // server doesn't support resume — restart from scratch.
                    val appendMode = response.code == 206 && existingBytes > 0

                    val body = response.body
                        ?: throw IOException("Empty update response body")

                    val totalBytes = if (body.contentLength() > 0) {
                        existingBytes + body.contentLength()
                    } else {
                        -1L
                    }

                    body.byteStream().use { input ->
                        var downloaded = existingBytes
                        var lastReportedPercent = -1

                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        val output = if (appendMode) {
                            FileOutputStream(tempFile, true)
                        } else {
                            FileOutputStream(tempFile)
                        }
                        output.use { out ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                downloaded += read

                                if (totalBytes > 0) {
                                    val percent =
                                        ((downloaded * 100) / totalBytes).toInt()
                                    if (percent != lastReportedPercent) {
                                        lastReportedPercent = percent
                                        NotificationManager.showUpdateDownloadProgress(
                                            appContext,
                                            downloaded,
                                            totalBytes
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            if (!tempFile.renameTo(outputFile)) {
                throw IOException("Failed to rename update temp file")
            }

            UpdateManager.onUpdateDownloaded(appContext, outputFile, version)
            printd("Update APK $version downloaded")
            Result.success()
        } catch (_: CancellationException) {
            // Keep the .part file so a retry can resume.
            printd("Update download canceled")
            Result.failure()
        } catch (e: Exception) {
            // Keep the .part file for resume; drop it if nothing was written.
            if (tempFile.exists() && tempFile.length() == 0L) {
                tempFile.delete()
            }
            NotificationManager.showUpdateDownloadFailed(appContext)
            printe(message = "Error downloading update: ${e.message}", exception = e)
            Result.failure()
        }
    }

    companion object {
        const val VERSION_KEY = "version"
        const val URL_KEY = "url"
    }
}
