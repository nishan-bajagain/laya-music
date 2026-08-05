package ca.ilianokokoro.umihi.music.data.datasources

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.data.repositories.LyricsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * LRCLIB — the sole source of synchronized lyrics.
 *
 * Every network outcome is explicit. A missing entry, a rate limit, a server
 * failure, an unreachable network, and an invalid response must not collapse
 * into the same nullable value.
 */
class LrcLibDataSource {

    companion object {
        /** The required exact-to-fuzzy lookup order. */
        internal val LOOKUP_PHASES = listOf("get-cached", "get", "search")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = UmihiHttpClient.client.newBuilder()
        .callTimeout(Constants.Lyrics.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    sealed class Outcome {
        data class Found(val track: Track) : Outcome()
        data object NotFound : Outcome()
        data object RateLimited : Outcome()
        data class ServerError(val httpCode: Int) : Outcome()
        data class NetworkError(val cause: Throwable) : Outcome()
        data class Malformed(val cause: Throwable) : Outcome()
    }

    data class Track(
        val id: Int?,
        val trackName: String?,
        val artistName: String?,
        val albumName: String?,
        val durationSeconds: Int?,
        val instrumental: Boolean,
        val plainLyrics: String?,
        val syncedLyrics: String?
    ) {
        fun hasSynced(): Boolean = !syncedLyrics.isNullOrBlank()
        fun hasPlain(): Boolean = !plainLyrics.isNullOrBlank()
    }

    /** Fast path: reads LRCLIB's own cache and does not proxy externally. */
    suspend fun getCached(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): Outcome = request(
        Constants.Lyrics.LRCLIB_GET_CACHED_URL,
        title,
        artist,
        album,
        durationSeconds
    )

    /** Full lookup: LRCLIB may perform an external lookup on a cache miss. */
    suspend fun get(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): Outcome = request(
        Constants.Lyrics.LRCLIB_GET_URL,
        title,
        artist,
        album,
        durationSeconds
    )

    /** Fuzzy lookup used after exact cached and full lookups. */
    suspend fun search(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = Constants.Lyrics.LRCLIB_SEARCH_URL
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext Outcome.Malformed(
                    IllegalStateException("Invalid LRCLIB search URL")
                )

            urlBuilder.addQueryParameter("track_name", title.trim())
            if (artist.isNotBlank()) {
                urlBuilder.addQueryParameter("artist_name", artist.trim())
            }
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("album_name", album.trim())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", Constants.Lyrics.LRCLIB_USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                classifyHttpStatus(response.code)?.let { return@withContext it }

                val body = response.body?.string()
                    ?: return@withContext Outcome.Malformed(IOException("Empty LRCLIB search body"))
                val entries = decodeSearchEntries(body)
                if (entries.isEmpty()) return@withContext Outcome.NotFound

                val best = selectBestSearchEntry(
                    entries = entries,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = durationSeconds
                ) ?: return@withContext Outcome.NotFound

                Outcome.Found(best.toTrack()).also {
                    LogHelper.printLyrics(
                        "Lyrics provider=lrclib endpoint=search " +
                            "synced=${best.hasUsableTimedLyrics()} plain=${best.hasPlain()}"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LyricsMalformedException) {
            Outcome.Malformed(e)
        } catch (e: SocketTimeoutException) {
            Outcome.NetworkError(e)
        } catch (e: IOException) {
            Outcome.NetworkError(e)
        } catch (e: Exception) {
            Outcome.Malformed(e)
        }
    }

    private suspend fun request(
        baseUrl: String,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): Outcome = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = baseUrl.toHttpUrlOrNull()
                ?.newBuilder()
                ?: return@withContext Outcome.Malformed(
                    IllegalStateException("Invalid LRCLIB URL: $baseUrl")
                )

            urlBuilder.addQueryParameter("track_name", title.trim())
            if (artist.isNotBlank()) {
                urlBuilder.addQueryParameter("artist_name", artist.trim())
            }
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("album_name", album.trim())
            }
            if (durationSeconds != null && durationSeconds > 0) {
                urlBuilder.addQueryParameter("duration", durationSeconds.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", Constants.Lyrics.LRCLIB_USER_AGENT)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                classifyHttpStatus(response.code)?.let { return@withContext it }

                val body = response.body?.string()
                    ?: return@withContext Outcome.Malformed(IOException("Empty LRCLIB body"))
                val entry = try {
                    json.decodeFromString<LrcLibEntry>(body)
                } catch (e: Exception) {
                    throw LyricsMalformedException("Invalid LRCLIB response", e)
                }
                val track = entry.toTrack()
                LogHelper.printLyrics(
                    "Lyrics provider=lrclib endpoint=$baseUrl " +
                        "synced=${track.hasSynced()} plain=${track.hasPlain()} " +
                        "instrumental=${track.instrumental}"
                )
                Outcome.Found(track)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LyricsMalformedException) {
            Outcome.Malformed(e)
        } catch (e: SocketTimeoutException) {
            Outcome.NetworkError(e)
        } catch (e: IOException) {
            Outcome.NetworkError(e)
        } catch (e: Exception) {
            Outcome.Malformed(e)
        }
    }

    internal fun classifyHttpStatus(code: Int): Outcome? = when {
        code == 404 -> Outcome.NotFound
        code == 429 -> Outcome.RateLimited
        code >= 500 -> Outcome.ServerError(code)
        code !in 200..299 -> Outcome.ServerError(code)
        else -> null
    }

    private fun decodeSearchEntries(body: String): List<LrcLibEntry> {
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw LyricsMalformedException("Invalid LRCLIB search JSON", e)
        }

        return when (element) {
            is JsonArray -> element.map {
                try {
                    json.decodeFromString<LrcLibEntry>(it.toString())
                } catch (e: Exception) {
                    throw LyricsMalformedException("Invalid LRCLIB search entry", e)
                }
            }

            is JsonObject -> listOf(
                try {
                    json.decodeFromString<LrcLibEntry>(element.toString())
                } catch (e: Exception) {
                    throw LyricsMalformedException("Invalid LRCLIB search object", e)
                }
            )

            else -> throw LyricsMalformedException("LRCLIB search response is not an object or array")
        }
    }

    /**
     * Test seam for the provider's two documented search response shapes.
     * The production request path uses the same decoder.
     */
    internal fun decodeSearchEntriesForTest(body: String): List<Track> =
        decodeSearchEntries(body).map { it.toTrack() }

    /**
     * Test seam for metadata-before-duration ranking. A near-duration result
     * with the wrong artist must not beat a correctly attributed track.
     */
    internal fun selectBestSearchEntryForTest(
        entries: List<Track>,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): Track? = entries
        .map { entry ->
            LrcLibEntry(
                id = entry.id,
                trackName = entry.trackName,
                artistName = entry.artistName,
                albumName = entry.albumName,
                duration = entry.durationSeconds?.toDouble(),
                instrumental = entry.instrumental,
                plainLyrics = entry.plainLyrics,
                syncedLyrics = entry.syncedLyrics
            )
        }
        .let {
            selectBestSearchEntry(it, title, artist, album, durationSeconds)?.toTrack()
        }

    private fun selectBestSearchEntry(
        entries: List<LrcLibEntry>,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): LrcLibEntry? {
        val metadataMatches = entries.filter {
            it.matchesMetadata(title, artist, album)
        }
        val titleMatches = entries.filter { it.matchesTitle(title) }
        val hasArtistSignal = artist.isNotBlank() &&
            entries.any { !it.artistName.isNullOrBlank() }
        val hasAlbumSignal = !album.isNullOrBlank() &&
            entries.any { !it.albumName.isNullOrBlank() }
        val candidates = when {
            metadataMatches.isNotEmpty() -> metadataMatches
            hasArtistSignal || hasAlbumSignal -> emptyList()
            else -> titleMatches
        }
        if (candidates.isEmpty()) return null

        val synced = candidates.filter { it.hasUsableTimedLyrics() }
        val pool = synced.ifEmpty { candidates }
        return pool.maxWithOrNull(
            compareBy<LrcLibEntry> {
                it.metadataScore(title, artist, album, durationSeconds)
            }.thenBy { if (it.hasUsableTimedLyrics()) 1 else 0 }
        )
    }

    @Serializable
    private data class LrcLibEntry(
        @SerialName("id") val id: Int? = null,
        @SerialName("trackName") val trackName: String? = null,
        @SerialName("artistName") val artistName: String? = null,
        @SerialName("albumName") val albumName: String? = null,
        @SerialName("duration") val duration: Double? = null,
        @SerialName("instrumental") val instrumental: Boolean? = null,
        @SerialName("plainLyrics") val plainLyrics: String? = null,
        @SerialName("syncedLyrics") val syncedLyrics: String? = null
    ) {
        fun toTrack() = Track(
            id = id,
            trackName = trackName,
            artistName = artistName,
            albumName = albumName,
            durationSeconds = duration?.toInt(),
            instrumental = instrumental == true,
            plainLyrics = plainLyrics?.takeIf { it.isNotBlank() },
            syncedLyrics = syncedLyrics?.takeIf { it.isNotBlank() }
        )

        fun hasPlain(): Boolean = !plainLyrics.isNullOrBlank()

        fun hasUsableTimedLyrics(): Boolean =
            LyricsParser.isUsableTimed(syncedLyrics)

        fun matchesTitle(expected: String): Boolean {
            val wanted = LyricsParser.normalizeMetadata(expected)
            val actual = LyricsParser.normalizeMetadata(trackName.orEmpty())
            if (wanted.isBlank() || actual.isBlank()) return false
            return wanted == actual ||
                wanted.contains(actual) ||
                actual.contains(wanted) ||
                tokenOverlap(wanted, actual) >= 0.5
        }

        fun matchesMetadata(
            expectedTitle: String,
            expectedArtist: String,
            expectedAlbum: String?
        ): Boolean {
            if (!matchesTitle(expectedTitle)) return false
            val wantedArtist = LyricsParser.normalizeMetadata(expectedArtist)
            val actualArtist = LyricsParser.normalizeMetadata(artistName.orEmpty())
            val artistMatches = wantedArtist.isBlank() ||
                actualArtist.isBlank() ||
                wantedArtist == actualArtist ||
                wantedArtist.contains(actualArtist) ||
                actualArtist.contains(wantedArtist) ||
                tokenOverlap(wantedArtist, actualArtist) >= 0.5
            if (!artistMatches) return false

            val wantedAlbum = LyricsParser.normalizeMetadata(expectedAlbum.orEmpty())
            val actualAlbum = LyricsParser.normalizeMetadata(albumName.orEmpty())
            return wantedAlbum.isBlank() || actualAlbum.isBlank() ||
                wantedAlbum == actualAlbum ||
                wantedAlbum.contains(actualAlbum) ||
                actualAlbum.contains(wantedAlbum)
        }

        fun metadataScore(
            expectedTitle: String,
            expectedArtist: String,
            expectedAlbum: String?,
            expectedDuration: Int?
        ): Int {
            var score = 0
            val wantedTitle = LyricsParser.normalizeMetadata(expectedTitle)
            val actualTitle = LyricsParser.normalizeMetadata(trackName.orEmpty())
            if (wantedTitle == actualTitle) score += 60
            else if (matchesTitle(expectedTitle)) score += 40

            val wantedArtist = LyricsParser.normalizeMetadata(expectedArtist)
            val actualArtist = LyricsParser.normalizeMetadata(artistName.orEmpty())
            if (wantedArtist.isNotBlank() && actualArtist.isNotBlank()) {
                if (wantedArtist == actualArtist) score += 30
                else if (tokenOverlap(wantedArtist, actualArtist) >= 0.5) score += 15
            }

            val wantedAlbum = LyricsParser.normalizeMetadata(expectedAlbum.orEmpty())
            val actualAlbum = LyricsParser.normalizeMetadata(albumName.orEmpty())
            if (wantedAlbum.isNotBlank() && actualAlbum.isNotBlank() &&
                wantedAlbum == actualAlbum
            ) {
                score += 10
            }

            if (expectedDuration != null && expectedDuration > 0 && duration != null) {
                val difference = kotlin.math.abs(duration.toInt() - expectedDuration)
                score += when {
                    difference <= 2 -> 15
                    difference <= 5 -> 8
                    difference <= 10 -> 2
                    else -> 0
                }
            }
            return score
        }

        private fun tokenOverlap(left: String, right: String): Double {
            val leftTokens = left.split(" ").filter(String::isNotBlank).toSet()
            val rightTokens = right.split(" ").filter(String::isNotBlank).toSet()
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
            return leftTokens.intersect(rightTokens).size.toDouble() /
                maxOf(leftTokens.size, rightTokens.size)
        }
    }

    private class LyricsMalformedException(message: String, cause: Throwable? = null) :
        IOException(message, cause)
}