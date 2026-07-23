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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Data source for the Better Lyrics API (https://better-lyrics.boidu.dev).
 * Provides synchronized LRC lyrics as primary source.
 */
class BetterLyricsDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = UmihiHttpClient.client.newBuilder()
        .callTimeout(Constants.Lyrics.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Search for synchronized lyrics using song metadata.
     * @return LRC-format synced lyrics string, or null if not found.
     */
    suspend fun getSyncedLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = Constants.Lyrics.BETTER_LYRICS_SEARCH_URL
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext null

            urlBuilder.addQueryParameter("trackName", title.trim())
            urlBuilder.addQueryParameter("artistName", artist.trim())
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("albumName", album.trim())
            }
            if (durationSeconds != null && durationSeconds > 0) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    LogHelper.printd("BetterLyrics: HTTP ${response.code} for $title - $artist")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val parsed = json.decodeFromString<BetterLyricsResponse>(body)

                parsed.syncedLyrics?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            LogHelper.printe("BetterLyrics fetch failed for $title - $artist: ${e.message}", exception = e)
            null
        }
    }

    @Serializable
    private data class BetterLyricsResponse(
        @SerialName("syncedLyrics") val syncedLyrics: String? = null,
        @SerialName("plainLyrics") val plainLyrics: String? = null,
        @SerialName("error") val error: String? = null
    )
}
