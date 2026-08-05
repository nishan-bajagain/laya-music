package ca.ilianokokoro.umihi.music.data.repositories

import android.content.Context
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.datasources.LrcLibDataSource
import ca.ilianokokoro.umihi.music.data.datasources.YtmPlainLyricsDataSource
import ca.ilianokokoro.umihi.music.models.CachedLyrics
import ca.ilianokokoro.umihi.music.models.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Single orchestration point for the lyrics contract:
 *
 *  1. Positive memory/Room cache
 *  2. LRCLIB /get-cached, /get, then /search for synchronized lyrics
 *  3. YouTube Music plain lyrics
 *  4. LRCLIB plain lyrics as a final fallback
 *
 * YouTube Music is deliberately never asked for timed lyrics. This keeps
 * synchronization independent from YouTube's private response schemas.
 */
class LyricsRepository(private val context: Context) {

    private val lyricsDao = AppDatabase.getInstance(context).lyricsRepository()
    private val lrcLib = LrcLibDataSource()
    private val ytmPlain = YtmPlainLyricsDataSource()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    companion object {
        private val memoryCache = ConcurrentHashMap<String, LyricsOutcome>()

        fun evictFromMemory(videoId: String) {
            memoryCache.remove(videoId)
        }

        fun peekMemoryCache(videoId: String): LyricsOutcome? = memoryCache[videoId]
    }

    sealed class LyricsOutcome {
        data class Synced(val lines: List<LyricLine>, val source: String) : LyricsOutcome()
        data class Plain(val lines: List<LyricLine>, val source: String) : LyricsOutcome()
        data object Instrumental : LyricsOutcome()
        data object NotFound : LyricsOutcome()
        data class NetworkError(val message: String) : LyricsOutcome()
        data object RateLimited : LyricsOutcome()
        data class Unknown(val message: String) : LyricsOutcome()

        val isPositive: Boolean
            get() = this is Synced || this is Plain || this is Instrumental
    }

    private data class Query(
        val title: String,
        val artist: String,
        val album: String?
    )

    private data class LrcScan(
        val synced: LrcLibDataSource.Track?,
        val instrumental: Boolean,
        val plain: String?,
        val sawRateLimited: Boolean,
        val sawNetwork: Boolean,
        val sawMalformed: Boolean,
        val timedOut: Boolean,
        val sawNotFound: Boolean
    )

    suspend fun getLyrics(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        forceRefresh: Boolean = false,
        onNetworkFetch: suspend () -> Unit = {}
    ): LyricsOutcome = withContext(Dispatchers.IO) {
        var cached: CachedLyrics? = null

        if (!forceRefresh) {
            memoryCache[videoId]
                ?.takeIf { it.isPositive }
                ?.let { return@withContext it }

            cached = readCache(videoId)
            cached
                ?.takeIf { !isCacheExpired(it) }
                ?.toSafeOutcome()
                ?.takeIf { it.isPositive && it !is LyricsOutcome.Plain }
                ?.let {
                    memoryCache[videoId] = it
                    return@withContext it
                }
        }

        if (forceRefresh) {
            memoryCache.remove(videoId)
            deleteCache(videoId)
        }

        onNetworkFetch()

        val queries = buildQueries(title, artist, album)
        LogHelper.printLyrics(
            "Lyrics fetch videoId=$videoId queries=${queries.size}"
        )

        val scan = scanLrcLib(queries, durationSeconds)
        if (scan.synced != null) {
            val synced = LyricsParser.parseLrc(scan.synced.syncedLyrics)
            if (synced.isNotEmpty()) {
                val entry = CachedLyrics(
                    videoId = videoId,
                    syncedLyrics = scan.synced.syncedLyrics,
                    plainLyrics = scan.synced.plainLyrics,
                    provider = "lrclib"
                )
                saveCache(entry)
                return@withContext LyricsOutcome.Synced(synced, "lrclib")
                    .also { memoryCache[videoId] = it }
            }
        }

        if (scan.instrumental) {
            val result = LyricsOutcome.Instrumental
            saveCache(CachedLyrics(videoId, null, null, "instrumental"))
            return@withContext result.also { memoryCache[videoId] = it }
        }

        val settings = try {
            DatastoreRepository(context).getSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("Lyrics settings lookup failed", exception = e)
            null
        }

        val ytmOutcome = withTimeoutOrNull(Constants.Lyrics.PROVIDER_TIMEOUT_MS) {
            ytmPlain.getPlainLyrics(videoId, settings)
        } ?: YtmPlainLyricsDataSource.Outcome.NetworkError(
            java.io.IOException("YTM plain lyrics request timed out")
        )

        when (ytmOutcome) {
            is YtmPlainLyricsDataSource.Outcome.Found -> {
                val lines = LyricsParser.parsePlain(ytmOutcome.text)
                if (lines.isNotEmpty()) {
                    val result = LyricsOutcome.Plain(lines, "ytm")
                    saveCache(
                        CachedLyrics(
                            videoId = videoId,
                            syncedLyrics = null,
                            plainLyrics = ytmOutcome.text,
                            provider = "ytm"
                        )
                    )
                    return@withContext result.also { memoryCache[videoId] = it }
                }
            }

            is YtmPlainLyricsDataSource.Outcome.NetworkError -> Unit
            is YtmPlainLyricsDataSource.Outcome.Malformed -> Unit
            YtmPlainLyricsDataSource.Outcome.NotFound -> Unit
        }

        scan.plain?.let { raw ->
            val lines = LyricsParser.parsePlain(raw)
            if (lines.isNotEmpty()) {
                val result = LyricsOutcome.Plain(lines, "lrclib")
                saveCache(
                    CachedLyrics(
                        videoId = videoId,
                        syncedLyrics = null,
                        plainLyrics = raw,
                        provider = "lrclib"
                    )
                )
                return@withContext result.also { memoryCache[videoId] = it }
            }
        }

        // A stale plain cache is useful when the provider is unreachable, but
        // never short-circuits the online synced lookup above.
        cached?.plainLyrics?.let { raw ->
            val lines = LyricsParser.parsePlain(raw)
            if (
                lines.isNotEmpty() &&
                (
                    scan.sawNetwork ||
                        scan.timedOut ||
                        ytmOutcome is YtmPlainLyricsDataSource.Outcome.NetworkError
                    )
            ) {
                return@withContext LyricsOutcome.Plain(lines, cached.provider)
            }
        }

        when {
            scan.sawRateLimited -> LyricsOutcome.RateLimited
            scan.sawNetwork || scan.timedOut ||
                ytmOutcome is YtmPlainLyricsDataSource.Outcome.NetworkError ->
                LyricsOutcome.NetworkError(
                    "Lyrics services could not be reached. Check your connection and retry."
                )

            scan.sawMalformed ||
                ytmOutcome is YtmPlainLyricsDataSource.Outcome.Malformed ->
                LyricsOutcome.Unknown("Lyrics services returned an invalid response.")

            else -> LyricsOutcome.NotFound
        }.also {
            if (it is LyricsOutcome.NotFound) {
                saveCache(CachedLyrics(videoId, null, null, "none"))
            }
            memoryCache[videoId] = it
        }
    }

    private suspend fun scanLrcLib(
        queries: List<Query>,
        durationSeconds: Int?
    ): LrcScan {
        var synced: LrcLibDataSource.Track? = null
        var instrumental = false
        var plain: String? = null
        var sawRateLimited = false
        var sawNetwork = false
        var sawMalformed = false
        var sawNotFound = false
        var timedOut = false

        val completed = withTimeoutOrNull(Constants.Lyrics.PROVIDER_TIMEOUT_MS) {
            for (phase in LrcLibDataSource.LOOKUP_PHASES) {
                for (query in queries) {
                    val outcome = retryTransient {
                        when (phase) {
                            "get-cached" -> lrcLib.getCached(
                                query.title,
                                query.artist,
                                query.album,
                                durationSeconds
                            )

                            "get" -> lrcLib.get(
                                query.title,
                                query.artist,
                                query.album,
                                durationSeconds
                            )

                            else -> lrcLib.search(
                                query.title,
                                query.artist,
                                query.album,
                                durationSeconds
                            )
                        }
                    }

                    when (outcome) {
                        is LrcLibDataSource.Outcome.Found -> {
                            val track = outcome.track
                            if (track.instrumental) {
                                instrumental = true
                            }
                            if (plain == null && track.hasPlain()) {
                                plain = track.plainLyrics
                            }
                            if (synced == null &&
                                track.hasSynced() &&
                                LyricsParser.isUsableTimed(track.syncedLyrics)
                            ) {
                                synced = track
                            }
                        }

                        LrcLibDataSource.Outcome.NotFound -> sawNotFound = true
                        LrcLibDataSource.Outcome.RateLimited -> sawRateLimited = true
                        is LrcLibDataSource.Outcome.ServerError -> sawNetwork = true
                        is LrcLibDataSource.Outcome.NetworkError -> sawNetwork = true
                        is LrcLibDataSource.Outcome.Malformed -> sawMalformed = true
                    }
                }

                // Once a synchronized result exists, later phases cannot
                // improve it without violating the exact->search contract.
                if (synced != null) break
            }
        }

        if (completed == null) timedOut = true
        return LrcScan(
            synced = synced,
            instrumental = instrumental,
            plain = plain,
            sawRateLimited = sawRateLimited,
            sawNetwork = sawNetwork,
            sawMalformed = sawMalformed,
            timedOut = timedOut,
            sawNotFound = sawNotFound
        )
    }

    private suspend fun retryTransient(
        block: suspend () -> LrcLibDataSource.Outcome
    ): LrcLibDataSource.Outcome {
        var delayMs = Constants.Lyrics.RETRY_INITIAL_DELAY_MS
        var result = block()
        repeat(Constants.Lyrics.RETRY_MAX_ATTEMPTS - 1) {
            if (result !is LrcLibDataSource.Outcome.RateLimited &&
                result !is LrcLibDataSource.Outcome.ServerError
            ) {
                return result
            }
            delay(delayMs)
            delayMs *= 2
            result = block()
        }
        return result
    }

    private fun buildQueries(
        title: String,
        artist: String,
        album: String?
    ): List<Query> {
        val cleanTitle = cleanTitle(title)
        val cleanArtist = cleanArtist(artist)
        return listOf(
            Query(cleanTitle, cleanArtist, album),
            Query(title.trim(), artist.trim(), album)
        ).filter { it.title.isNotBlank() }.distinct()
    }

    private fun cleanTitle(value: String): String = normalizeUnicode(
        value
            .replace(Regex("""\s*\((?:Official\s+)?(?:Audio|Lyrics?|Music Video|Visualizer|Live)[^)]*\)"""), "")
            .replace(Regex("""\s*\[(?:Official\s+)?(?:Audio|Lyrics?|Music Video|Visualizer|Live)[^]]*]"""), "")
            .replace(
                Regex(
                    """\s*[-–—:]\s*(?:official\s*)?(?:music\s*)?(?:audio|video|lyrics?|visualizer).*$""",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .replace(Regex("""\s+(?:feat\.|ft\.|featuring)\s+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    )

    private fun cleanArtist(value: String): String = normalizeUnicode(
        value.split(",", "&", " x ", " X ").first()
            .replace(Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*VEVO\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    )

    private fun normalizeUnicode(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)

    private suspend fun readCache(videoId: String): CachedLyrics? = try {
        lyricsDao.getLyrics(videoId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LogHelper.printe("Lyrics cache read failed for $videoId", exception = e)
        null
    }

    private suspend fun saveCache(entry: CachedLyrics) {
        try {
            lyricsDao.saveLyrics(entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("Lyrics cache write failed for ${entry.videoId}", exception = e)
        }
    }

    private suspend fun deleteCache(videoId: String) {
        try {
            lyricsDao.deleteLyrics(videoId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("Lyrics cache delete failed for $videoId", exception = e)
        }
    }

    private fun isCacheExpired(entry: CachedLyrics): Boolean {
        val age = System.currentTimeMillis() - entry.cachedAtMs
        val ttl = when {
            entry.provider == "none" -> Constants.Lyrics.NEGATIVE_CACHE_TTL_MS
            entry.provider == "instrumental" -> Constants.Lyrics.CACHE_TTL_MS
            entry.syncedLyrics.isNullOrBlank() && !entry.plainLyrics.isNullOrBlank() ->
                Constants.Lyrics.PLAIN_CACHE_TTL_MS

            else -> Constants.Lyrics.CACHE_TTL_MS
        }
        return age > ttl
    }

    private fun CachedLyrics.toSafeOutcome(): LyricsOutcome? {
        if (provider == "instrumental") return LyricsOutcome.Instrumental

        timedLinesJson
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching {
                    json.decodeFromString<List<LyricLine>>(encoded)
                }.getOrNull()
            }
            ?.let(LyricsParser::normalizeTimed)
            ?.takeIf(List<LyricLine>::isNotEmpty)
            ?.let { return LyricsOutcome.Synced(it, provider) }

        syncedLyrics?.let { raw ->
            LyricsParser.parseLrc(raw)
                .takeIf(List<LyricLine>::isNotEmpty)
                ?.let { return LyricsOutcome.Synced(it, provider) }
        }

        plainLyrics?.let { raw ->
            LyricsParser.parsePlain(raw)
                .takeIf(List<LyricLine>::isNotEmpty)
                ?.let { return LyricsOutcome.Plain(it, provider) }
        }

        return null
    }
}