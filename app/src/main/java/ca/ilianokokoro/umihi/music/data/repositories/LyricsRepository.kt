package ca.ilianokokoro.umihi.music.data.repositories

import android.content.Context
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.ConnectivityHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.datasources.BetterLyricsDataSource
import ca.ilianokokoro.umihi.music.data.datasources.LrcLibDataSource
import ca.ilianokokoro.umihi.music.data.datasources.LyricsTransientException
import ca.ilianokokoro.umihi.music.data.datasources.YtmLyricsDataSource
import ca.ilianokokoro.umihi.music.models.CachedLyrics
import ca.ilianokokoro.umihi.music.models.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates lyrics fetching from multiple providers with local caching.
 *
 * Provider priority (synced lyrics are always preferred over plain):
 *   1. In-process memory cache (instant re-renders on track revisit)
 *   2. Local Room cache (if fresh, or stale when offline)
 *   3. YouTube Music — timedLyricsRenderer (synced LRC) if available
 *   4. Better Lyrics — synchronized LRC
 *   5. LRCLIB — synchronized LRC (with exponential back-off on 429/5xx)
 *   6. YouTube Music — plain text
 *   7. LRCLIB — plain text
 *
 * The [onNetworkFetch] callback is invoked when a network request is needed
 * (cache miss), allowing the ViewModel to update the UI loading sub-state.
 */
class LyricsRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val lyricsDao = db.lyricsRepository()
    private val ytmLyrics = YtmLyricsDataSource()
    private val betterLyrics = BetterLyricsDataSource()
    private val lrcLib = LrcLibDataSource()

    // ── In-process memory cache (survives ViewModel recreation if singleton) ──
    companion object {
        private val memoryCache = ConcurrentHashMap<String, LyricsResult>()

        /** Evict a single entry — call when forceRefresh is used. */
        fun evictFromMemory(videoId: String) { memoryCache.remove(videoId) }

        /** Optionally inspect from the ViewModel before launching a coroutine. */
        fun peekMemoryCache(videoId: String): LyricsResult? = memoryCache[videoId]
    }

    /**
     * Fetch lyrics for a song, using in-process memory then local Room cache before network.
     *
     * @param forceRefresh  Bypass all caches and fetch fresh from the network.
     * @param onNetworkFetch Called right before the first network request fires, so the
     *   ViewModel can transition to a "fetching synced lyrics…" state.
     */
    suspend fun getLyrics(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        forceRefresh: Boolean = false,
        onNetworkFetch: () -> Unit = {}
    ): LyricsResult = withContext(Dispatchers.IO) {

        // ── 1. Memory cache (instant) ────────────────────────────────────────
        if (!forceRefresh) {
            memoryCache[videoId]?.let { return@withContext it }
        }

        // ── 2. Room cache ────────────────────────────────────────────────────
        val cached = try {
            lyricsDao.getLyrics(videoId)
        } catch (e: Exception) {
            LogHelper.printe("Lyrics DAO read failed for $videoId: ${e.message}", exception = e)
            null
        }

        if (!forceRefresh && cached != null && !isCacheExpired(cached)) {
            val result = cached.toResult()
            memoryCache[videoId] = result
            return@withContext result
        }

        // ── 3. Offline fallback — serve stale cache rather than failing ──────
        if (!ConnectivityHelper.isNetworkAvailable(context)) {
            val result = cached?.let {
                if (it.provider != "none") it.toResult() else LyricsResult.Offline
            } ?: LyricsResult.Offline
            if (result !is LyricsResult.Offline) memoryCache[videoId] = result
            return@withContext result
        }

        // Notify the ViewModel that we are now hitting the network
        onNetworkFetch()

        if (forceRefresh) memoryCache.remove(videoId)

        val cleanTitle = cleanTitle(title)
        val cleanArtist = cleanArtist(artist)

        LogHelper.printd("Fetching lyrics for: \"$cleanTitle\" by \"$cleanArtist\"")

        val settings = try {
            DatastoreRepository(context).getSettings()
        } catch (_: Exception) { null }

        // ── 4. Parallel fetch from all providers ─────────────────────────────
        val ytmDeferred = async {
            try { ytmLyrics.getLyrics(videoId = videoId, settings = settings) }
            catch (e: Exception) {
                LogHelper.printe("YTM lyrics failed: ${e.message}", exception = e)
                null
            }
        }
        val betterDeferred = async {
            try {
                betterLyrics.getSyncedLyrics(
                    title = cleanTitle, artist = cleanArtist,
                    album = album, durationSeconds = durationSeconds
                )
            } catch (e: Exception) {
                LogHelper.printe("BetterLyrics failed: ${e.message}", exception = e)
                null
            }
        }
        val lrclibDeferred = async {
            // LRCLIB gets exponential back-off on transient failures (429 / 5xx).
            // 404 returns null immediately (no retry).
            try {
                retryOnTransient {
                    lrcLib.getLyrics(
                        title = cleanTitle, artist = cleanArtist,
                        album = album, durationSeconds = durationSeconds
                    ) ?: lrcLib.getLyrics(
                        title = cleanTitle, artist = cleanArtist,
                        album = null, durationSeconds = durationSeconds
                    )
                }
            } catch (e: Exception) {
                LogHelper.printe("LRCLIB failed: ${e.message}", exception = e)
                null
            }
        }

        val ytmResult    = ytmDeferred.await()
        val betterResult = betterDeferred.await()
        val lrclibResult = lrclibDeferred.await()

        // ── Priority: synced > plain; YTM > Better > LRCLIB within each tier ─

        // YTM synced
        if (ytmResult?.syncedLrc != null) {
            val entry = CachedLyrics(videoId, ytmResult.syncedLrc, null, "ytm")
            safeCache(entry)
            return@withContext entry.toResult().also { memoryCache[videoId] = it }
        }

        // Better Lyrics synced
        if (!betterResult.isNullOrBlank()) {
            val entry = CachedLyrics(videoId, betterResult, null, "better-lyrics")
            safeCache(entry)
            return@withContext entry.toResult().also { memoryCache[videoId] = it }
        }

        // LRCLIB — instrumental / synced / plain
        when {
            lrclibResult?.isInstrumental == true -> {
                safeCache(CachedLyrics(videoId, null, null, "instrumental"))
                return@withContext LyricsResult.Instrumental.also { memoryCache[videoId] = it }
            }
            !lrclibResult?.syncedLyrics.isNullOrBlank() -> {
                val entry = CachedLyrics(
                    videoId,
                    lrclibResult!!.syncedLyrics!!,
                    lrclibResult.plainLyrics,
                    "lrclib"
                )
                safeCache(entry)
                return@withContext entry.toResult().also { memoryCache[videoId] = it }
            }
        }

        // YTM plain text
        if (!ytmResult?.plainText.isNullOrBlank()) {
            val entry = CachedLyrics(videoId, null, ytmResult!!.plainText, "ytm")
            safeCache(entry)
            return@withContext entry.toResult().also { memoryCache[videoId] = it }
        }

        // LRCLIB plain text
        if (!lrclibResult?.plainLyrics.isNullOrBlank()) {
            val entry = CachedLyrics(videoId, null, lrclibResult!!.plainLyrics, "lrclib")
            safeCache(entry)
            return@withContext entry.toResult().also { memoryCache[videoId] = it }
        }

        // Nothing found — negative cache to avoid repeated hammering
        safeCache(CachedLyrics(videoId, null, null, "none"))
        LyricsResult.NotFound.also { memoryCache[videoId] = it }
    }

    // ── Retry with exponential back-off (only for [LyricsTransientException]) ─

    private suspend fun <T> retryOnTransient(
        maxAttempts: Int = Constants.Lyrics.RETRY_MAX_ATTEMPTS,
        initialDelayMs: Long = Constants.Lyrics.RETRY_INITIAL_DELAY_MS,
        block: suspend () -> T
    ): T {
        var delayMs = initialDelayMs
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: LyricsTransientException) {
                lastException = e
                if (attempt < maxAttempts - 1) {
                    LogHelper.printd("LRCLIB transient error (attempt ${attempt + 1}/$maxAttempts): ${e.message}. Retrying in ${delayMs}ms")
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastException ?: IllegalStateException("retryOnTransient: exhausted with no exception captured")
    }

    private suspend fun safeCache(entry: CachedLyrics) {
        try {
            lyricsDao.saveLyrics(entry)
        } catch (e: Exception) {
            LogHelper.printe(message = "Failed to cache lyrics: ${e.message}", exception = e)
        }
    }

    private fun isCacheExpired(entry: CachedLyrics): Boolean {
        val age = System.currentTimeMillis() - entry.cachedAtMs
        val ttl = when (entry.provider) {
            "none" -> Constants.Lyrics.NEGATIVE_CACHE_TTL_MS
            "instrumental" -> Constants.Lyrics.CACHE_TTL_MS
            else -> Constants.Lyrics.CACHE_TTL_MS
        }
        return age > ttl
    }

    // ── Metadata cleaners ────────────────────────────────────────────────────

    /**
     * Strip YouTube-specific suffixes and noise from track titles, then NFKC-normalise
     * to fix inconsistent Unicode characters / diacritics before API lookups.
     */
    private fun cleanTitle(title: String): String =
        normalizeUnicode(
            title
                .replace(Regex("""\s*\(Official[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\[Official[^\]]*]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(Audio[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\[Audio[^\]]*]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(Lyrics?[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\[Lyrics?[^\]]*]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(Music Video[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(Visualizer[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(Remaster[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\[Remaster[^\]]*]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(Live[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\[Live[^\]]*]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\[HD[^\]]*]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(ft\.[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(feat\.[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*\(featuring[^)]*\)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+feat\..*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+ft\..*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+featuring\s+.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
        )

    /**
     * Strip YouTube auto-channel suffixes from artist names, then NFKC-normalise.
     * When multiple artists are listed, use only the first.
     */
    private fun cleanArtist(artist: String): String =
        normalizeUnicode(
            artist
                .split(",", "&", " x ", " X ").first()
                .replace(Regex("""\s*-\s*Topic\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""VEVO\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*Official\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
        )

    /** NFKC Unicode normalisation fixes inconsistent diacritics / fullwidth characters. */
    private fun normalizeUnicode(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)

    // ── LRC / plain parsing ──────────────────────────────────────────────────

    private fun CachedLyrics.toResult(): LyricsResult {
        if (provider == "instrumental") return LyricsResult.Instrumental

        val lrc = syncedLyrics
        val plain = plainLyrics

        return when {
            !lrc.isNullOrBlank() -> {
                val lines = parseLrc(lrc)
                when {
                    lines.isNotEmpty() -> LyricsResult.Synced(lines, provider)
                    !plain.isNullOrBlank() -> LyricsResult.Plain(parsePlain(plain), provider)
                    else -> LyricsResult.NotFound
                }
            }
            !plain.isNullOrBlank() -> LyricsResult.Plain(parsePlain(plain), provider)
            else -> LyricsResult.NotFound
        }
    }

    /**
     * Parse LRC format into timed [LyricLine] list.
     * Supports [mm:ss.xx], [mm:ss:xx], and [mm:ss] timestamp formats.
     * Multiple timestamps per line are supported (e.g. [00:01.00][01:30.00]text).
     * Blank lines and malformed lines are skipped — never crash on bad input.
     */
    private fun parseLrc(lrc: String): List<LyricLine> {
        val timeRegex = Regex("""\[(\d{1,2}):(\d{2})[.:]?(\d{0,3})]""")
        val lines = mutableListOf<LyricLine>()

        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val matches = timeRegex.findAll(line).toList()
            if (matches.isEmpty()) continue

            val lastMatch = matches.last()
            val text = line.substring(lastMatch.range.last + 1).trim()
            if (text.isEmpty()) continue   // skip blank/instrumental gap markers

            for (match in matches) {
                val (min, sec, milliStr) = match.destructured
                val centis = milliStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                val timeMs = (min.toLong() * 60_000L) + (sec.toLong() * 1_000L) + centis
                lines.add(LyricLine(timeMs = timeMs, text = text))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    private fun parsePlain(plain: String): List<LyricLine> =
        plain.lines()
            .map { LyricLine(timeMs = null, text = it.trim()) }
            .filter { it.text.isNotEmpty() }

    // ── Result types ─────────────────────────────────────────────────────────

    sealed class LyricsResult {
        data class Synced(val lines: List<LyricLine>, val provider: String) : LyricsResult()
        data class Plain(val lines: List<LyricLine>, val provider: String) : LyricsResult()
        data object NotFound : LyricsResult()
        data object Instrumental : LyricsResult()
        data object Offline : LyricsResult()
    }
}
