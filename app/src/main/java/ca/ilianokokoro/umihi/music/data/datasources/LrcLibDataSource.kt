package ca.ilianokokoro.umihi.music.data.datasources

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Signals that a request failed with a transient error (429 or 5xx) and should be retried.
 * 404 "not found" is NOT transient and should never trigger a retry.
 */
class LyricsTransientException(message: String) : Exception(message)

/**
 * Data source for the LRCLIB public API (https://lrclib.net).
 * Provides both synchronized LRC and plain-text lyrics as fallback source.
 *
 * HTTP error handling:
 *   404  → null (not found, no retry)
 *   429 / 5xx → [LyricsTransientException] so callers can retry with back-off
 *   other non-2xx → null (no retry)
 */
class LrcLibDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = UmihiHttpClient.client.newBuilder()
        .callTimeout(Constants.Lyrics.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Search LRCLIB for lyrics matching song metadata.
     * Returns the best-matching result with synced or plain lyrics, or null if not found.
     * Throws [LyricsTransientException] on 429 / 5xx so the caller can retry.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        // First try exact get endpoint (most accurate match)
        val exact = tryExactGet(title, artist, album, durationSeconds)
        if (exact != null && (exact.syncedLyrics != null || exact.plainLyrics != null)) {
            return@withContext exact
        }

        // Fall back to search endpoint
        trySearch(title, artist, album, durationSeconds)
    }

    private suspend fun tryExactGet(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = Constants.Lyrics.LRCLIB_GET_URL
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext null

            urlBuilder.addQueryParameter("track_name", title.trim())
            urlBuilder.addQueryParameter("artist_name", artist.trim())
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("album_name", album.trim())
            }
            if (durationSeconds != null && durationSeconds > 0) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("Lrclib-Client", "Laya Music v1.0 (https://github.com/nishan-bajagain/laya-music)")
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> return@withContext null
                    response.code == 429 || response.code >= 500 ->
                        throw LyricsTransientException("LRCLIB transient error: HTTP ${response.code}")
                    !response.isSuccessful -> return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<LrcLibEntry>(body).toResult()
            }
        } catch (e: LyricsTransientException) {
            throw e   // propagate — let the caller retry
        } catch (e: Exception) {
            LogHelper.printd("LRCLIB exact get failed: ${e.message}")
            null
        }
    }

    private suspend fun trySearch(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): LrcLibResult? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = Constants.Lyrics.LRCLIB_SEARCH_URL
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext null

            urlBuilder.addQueryParameter("track_name", title.trim())
            urlBuilder.addQueryParameter("artist_name", artist.trim())

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("Lrclib-Client", "Laya Music v1.0 (https://github.com/nishan-bajagain/laya-music)")
                .build()

            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> return@withContext null
                    response.code == 429 || response.code >= 500 ->
                        throw LyricsTransientException("LRCLIB transient error: HTTP ${response.code}")
                    !response.isSuccessful -> return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val entries = json.decodeFromString<List<LrcLibEntry>>(body)

                // Prefer synced results; within each tier pick closest by duration.
                val best = if (durationSeconds != null && durationSeconds > 0) {
                    val synced = entries.filter { !it.syncedLyrics.isNullOrBlank() }
                    val pool = synced.ifEmpty { entries }
                    pool.minByOrNull { entry ->
                        val dur = entry.duration?.toInt() ?: Int.MAX_VALUE
                        kotlin.math.abs(dur - durationSeconds)
                    }
                } else {
                    entries.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                        ?: entries.firstOrNull()
                }

                best?.toResult()
            }
        } catch (e: LyricsTransientException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("LRCLIB search failed for $title - $artist: ${e.message}", exception = e)
            null
        }
    }

    @Serializable
    private data class LrcLibEntry(
        @SerialName("id") val id: Int? = null,
        @SerialName("trackName") val trackName: String? = null,
        @SerialName("artistName") val artistName: String? = null,
        @SerialName("albumName") val albumName: String? = null,
        // LRCLIB returns duration as a floating-point number (e.g. 213.0),
        // so this must be Double?, not Int?, to avoid a SerializationException.
        @SerialName("duration") val duration: Double? = null,
        @SerialName("syncedLyrics") val syncedLyrics: String? = null,
        @SerialName("plainLyrics") val plainLyrics: String? = null,
        @SerialName("instrumental") val instrumental: Boolean? = null
    ) {
        fun toResult(): LrcLibResult? {
            if (instrumental == true) return LrcLibResult(null, null, isInstrumental = true)
            return LrcLibResult(
                syncedLyrics = syncedLyrics?.takeIf { it.isNotBlank() },
                plainLyrics = plainLyrics?.takeIf { it.isNotBlank() }
            )
        }
    }

    data class LrcLibResult(
        val syncedLyrics: String?,
        val plainLyrics: String?,
        val isInstrumental: Boolean = false
    )
}
