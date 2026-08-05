package ca.ilianokokoro.umihi.music.core.youtube

import androidx.media3.common.Player
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper.formatDecimal
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeAuthHelper.applyHeaders
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

object YoutubeStatsTracker {

    private data class PlaybackTrackingUrls(
        val playbackUrl: String?,
        val watchtimeUrl: String?,
    )

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        LogHelper.printe("Unhandled error in playback stats tracking: $throwable")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    @Volatile
    private var resolveJob: Job? = null

    @Volatile
    private var trackingJob: Job? = null

    @Volatile
    private var currentVideoId: String? = null

    @Volatile
    private var currentWatchtimeUrl: String? = null

    @Volatile
    private var currentSettings: UmihiSettings? = null

    @Volatile
    private var currentPlaylistId: String? = null

    @Volatile
    private var currentReferrer: String? = null

    @Volatile
    private var isPaused = false

    /** Tracks the last reported position so we can resume correctly after pause/seek. */
    @Volatile
    private var lastReportedPositionSec = 0f

    /**
     * The last position observed while the session still owned the player.
     * This is important during a Media3 transition: by the time the transition
     * callback is delivered, currentPosition already belongs to the next song.
     */
    @Volatile
    private var lastKnownPositionSec = 0f

    @Volatile
    private var lastKnownDurationSec = 0f

    /** The CPN (client playback nonce) for the current tracking session. */
    @Volatile
    private var currentCpn: String? = null

    @Volatile
    private var initJob: Job? = null

    private val sessionMutex = Mutex()
    private val requestMutex = Mutex()

    fun hasSessionFor(videoId: String): Boolean =
        currentVideoId == videoId

    fun stopPlaybackTracking() {
        scope.launch {
            sessionMutex.withLock {
                finalizeCurrentSession("stop")
            }
        }
    }

    /**
     * The service uses this suspend variant from onDestroy so the final
     * watchtime request is given a chance to leave before Android tears down
     * the process.
     */
    suspend fun stopPlaybackTrackingAndFlush() {
        sessionMutex.withLock {
            finalizeCurrentSession("service teardown")
        }
    }

    /** Called when playback is paused — checkpoint the interval before stopping. */
    fun onPlaybackPaused() {
        isPaused = true
        scope.launch {
            sessionMutex.withLock {
                flushCurrentSession("pause")
            }
        }
    }

    /** Called when playback resumes — resume watchtime updates from current position. */
    fun onPlaybackResumed() {
        isPaused = false
    }

    /** Called when the user seeks — reset the last reported position so the next
     *  watchtime update reflects the new position. */
    fun onSeekPerformed() {
        scope.launch {
            sessionMutex.withLock {
                val videoId = currentVideoId ?: return@withLock
                val position = currentPositionFor(videoId) ?: return@withLock
                lastKnownPositionSec = position.first
                lastKnownDurationSec = position.second
                // The skipped interval must not be attributed to the new
                // position. Start the next interval at the seek destination.
                lastReportedPositionSec = position.first
            }
        }
    }

    fun onPlaybackStarted(
        videoId: String,
        settings: UmihiSettings,
    ) {
        scope.launch {
            sessionMutex.withLock {
                finalizeCurrentSession("track transition")

                if (!settings.sendPlaybackData || settings.cookies.isEmpty()) {
                    LogHelper.printd(
                        "Playback tracking skipped: sendPlaybackData=${settings.sendPlaybackData}, " +
                            "hasCookies=${settings.cookies.isNotEmpty()}, " +
                            "hasDataSyncId=${!settings.dataSyncId.isNullOrBlank()}"
                    )
                    return@withLock
                }

                if (settings.dataSyncId.isNullOrBlank()) {
                    LogHelper.printd(
                        "Playback tracking has no dataSyncId; continuing with cookie authentication for $videoId"
                    )
                }

                currentVideoId = videoId
                currentSettings = settings
                currentPlaylistId = null
                currentReferrer = "${Constants.YoutubeApi.ORIGIN}/watch?v=$videoId"
                isPaused = true

                resolveJob = scope.launch {
                    try {
                        // A media transition can be delivered before the
                        // decoder is ready. Wait for the actual playing state
                        // so a queued item is not reported as listened.
                        if (!awaitReadyAndPlaying(videoId)) {
                            LogHelper.printd(
                                "Playback tracking not started: $videoId was never ready and playing"
                            )
                            return@launch
                        }

                        val playerResponse = YoutubeApiClient.getPlayerInfo(
                            videoId = videoId,
                            visitorData = visitorData,
                            settings = settings,
                            musicOrigin = true,
                        )
                        if (!isActive || currentVideoId != videoId) return@launch

                        val trackingUrls = extractTrackingUrls(playerResponse)
                        val playbackUrl = trackingUrls.playbackUrl
                        val watchtimeUrl = trackingUrls.watchtimeUrl

                        if (playbackUrl == null || watchtimeUrl == null) {
                            LogHelper.printe(
                                "No tracking URLs in player response for $videoId " +
                                    "(hasDataSyncId=${!settings.dataSyncId.isNullOrBlank()})"
                            )
                            return@launch
                        }

                        startPlaybackTracking(
                            videoId = videoId,
                            playbackUrl = playbackUrl,
                            watchtimeUrl = watchtimeUrl,
                            settings = settings,
                            playlistId = null,
                            referrer = currentReferrer,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogHelper.printe(
                            "Failed to start playback tracking for $videoId: ${e.message}",
                            exception = e
                        )
                    }
                }
            }
        }
    }

    private suspend fun awaitReadyAndPlaying(videoId: String): Boolean {
        repeat(40) {
            val ready = withContext(Dispatchers.Main.immediate) {
                val controller = PlayerManager.currentController
                controller?.currentMediaItem?.mediaId == videoId &&
                    controller.isPlaying &&
                    controller.playbackState == Player.STATE_READY
            }
            if (ready) return true
            delay(250L)
        }
        return false
    }

    private suspend fun finalizeCurrentSession(reason: String) {
        resolveJob?.cancel()
        resolveJob = null
        initJob?.cancel()
        initJob = null
        trackingJob?.cancel()
        trackingJob = null

        val videoId = currentVideoId
        val settings = currentSettings
        val watchtimeUrl = currentWatchtimeUrl
        val cpn = currentCpn
        val session = if (
            videoId != null && settings != null && watchtimeUrl != null && cpn != null
        ) {
            PlaybackSession(
                videoId = videoId,
                watchtimeUrl = watchtimeUrl,
                settings = settings,
                playlistId = currentPlaylistId,
                referrer = currentReferrer,
                cpn = cpn
            )
        } else {
            null
        }

        currentVideoId = null
        currentWatchtimeUrl = null
        currentSettings = null
        currentPlaylistId = null
        currentReferrer = null
        currentCpn = null
        isPaused = false

        if (session != null) {
            flushSession(session, reason)
        }

        lastReportedPositionSec = 0f
        lastKnownPositionSec = 0f
        lastKnownDurationSec = 0f
    }

    private suspend fun flushCurrentSession(reason: String) {
        val videoId = currentVideoId ?: return
        val settings = currentSettings ?: return
        val watchtimeUrl = currentWatchtimeUrl ?: return
        val cpn = currentCpn ?: return
        flushSession(
            PlaybackSession(
                videoId = videoId,
                watchtimeUrl = watchtimeUrl,
                settings = settings,
                playlistId = currentPlaylistId,
                referrer = currentReferrer,
                cpn = cpn
            ),
            reason
        )
    }

    private data class PlaybackSession(
        val videoId: String,
        val watchtimeUrl: String,
        val settings: UmihiSettings,
        val playlistId: String?,
        val referrer: String?,
        val cpn: String,
    )

    private suspend fun flushSession(session: PlaybackSession, reason: String) {
        val livePosition = currentPositionFor(session.videoId)
        val endPosition = maxOf(
            lastKnownPositionSec,
            livePosition?.first ?: 0f
        )
        val duration = maxOf(lastKnownDurationSec, livePosition?.second ?: 0f)
        val end = if (duration > 0f) endPosition.coerceAtMost(duration) else endPosition

        if (end <= lastReportedPositionSec +
            Constants.Player.Tracking.POSITION_TOLERANCE_SEC
        ) {
            return
        }

        requestMutex.withLock {
            sendWatchtimeUpdateWithRetry(
                baseUrl = session.watchtimeUrl,
                cpn = session.cpn,
                st = lastReportedPositionSec.formatDecimal(),
                et = end.formatDecimal(),
                settings = session.settings,
                playlistId = session.playlistId,
                referrer = session.referrer,
            )
        }
        lastReportedPositionSec = end
        LogHelper.printd(
            "Watchtime flush ($reason): ${lastReportedPositionSec.formatDecimal()} → " +
                "${end.formatDecimal()} (${session.videoId})"
        )
    }

    private fun startPlaybackTracking(
        videoId: String,
        playbackUrl: String,
        watchtimeUrl: String,
        settings: UmihiSettings,
        playlistId: String?,
        referrer: String?,
    ) {
        val cpn = UmihiHelper.Cpn.generate()
        currentCpn = cpn
        currentWatchtimeUrl = watchtimeUrl
        currentSettings = settings
        currentPlaylistId = playlistId
        currentReferrer = referrer
        isPaused = false

        initJob = scope.launch {
            sendInitPlaybackWithRetry(
                baseUrl = playbackUrl, cpn = cpn, settings = settings,
                playlistId = playlistId, referrer = referrer,
            )
            LogHelper.printd("Playback tracking started for $videoId (cpn=$cpn)")
        }

        trackingJob = scope.launch {
            lastReportedPositionSec = 0f

            while (isActive) {
                delay(Constants.Player.Tracking.WATCHTIME_INTERVAL_MS.milliseconds)

                if (currentVideoId != videoId) break

                val (posSec, durSec) = currentPositionFor(videoId) ?: continue
                lastKnownPositionSec = posSec
                lastKnownDurationSec = durSec

                // Skip reporting while paused — resume will pick up from current position
                if (isPaused) continue

                if (posSec >= lastReportedPositionSec + Constants.Player.Tracking.POSITION_TOLERANCE_SEC) {
                    val nextCheckpoint = posSec + Constants.Player.Tracking.WATCHTIME_ADVANCE_SEC

                    if (nextCheckpoint < durSec) {
                        val st = lastReportedPositionSec.formatDecimal()
                        val et = posSec.formatDecimal()

                        sendWatchtimeUpdateWithRetry(
                            baseUrl = watchtimeUrl, cpn = cpn, st = st, et = et,
                            settings = settings, playlistId = playlistId, referrer = referrer,
                        )
                        LogHelper.printd("Watchtime update: $st → $et ($videoId)")

                        lastReportedPositionSec = posSec
                    } else {
                        // Final watchtime complete — send it but do NOT cancel the job
                        // from within itself (race condition). Just mark completion.
                        sendWatchtimeCompleteWithRetry(
                            baseUrl = watchtimeUrl, cpn = cpn, durationSeconds = durSec,
                            settings = settings, playlistId = playlistId, referrer = referrer,
                        )
                        LogHelper.printd("Watchtime complete: $durSec ($videoId)")

                        // Break out of the loop cleanly instead of cancelling self
                        break
                    }
                }
            }
        }
    }

    private suspend fun currentPositionFor(videoId: String): Pair<Float, Float>? =
        withContext(Dispatchers.Main.immediate) {
            val controller = PlayerManager.currentController ?: return@withContext null
            if (controller.currentMediaItem?.mediaId != videoId) {
                return@withContext null
            }

            val positionMs = controller.currentPosition
            val durationMs = controller.duration
            if (positionMs < 0L || durationMs <= 0L) {
                return@withContext null
            }

            (positionMs / 1000f) to (durationMs / 1000f)
        }

    private fun extractTrackingUrls(jsonString: String): PlaybackTrackingUrls {
        return try {
            val root = JSONObject(jsonString)
            val tracking =
                root.optJSONObject("playbackTracking") ?: return PlaybackTrackingUrls(null, null)

            fun extractBaseUrl(key: String): String? {
                val obj = tracking.optJSONObject(key) ?: return null
                val url = obj.optString("baseUrl", "")
                return url.ifBlank { null }
            }

            PlaybackTrackingUrls(
                playbackUrl = extractBaseUrl("videostatsPlaybackUrl"),
                watchtimeUrl = extractBaseUrl("videostatsWatchtimeUrl"),
            )
        } catch (e: Exception) {
            LogHelper.printe(
                "Failed to parse player response for tracking URLs: ${e.message}",
                exception = e
            )
            PlaybackTrackingUrls(null, null)
        }
    }

    /**
     * Send the init playback request with retry on transient failures (429/5xx).
     * YouTube's stats backend occasionally returns 429 under load; retrying with
     * backoff makes history reporting significantly more reliable.
     */
    private suspend fun sendInitPlaybackWithRetry(
        baseUrl: String,
        cpn: String,
        settings: UmihiSettings,
        playlistId: String? = null,
        referrer: String? = null,
    ) {
        retryOnTransient(maxAttempts = 3) {
            sendInitPlayback(
                baseUrl = baseUrl, cpn = cpn, settings = settings,
                playlistId = playlistId, referrer = referrer,
            )
        }
    }

    private suspend fun sendWatchtimeUpdateWithRetry(
        baseUrl: String,
        cpn: String,
        st: String,
        et: String,
        settings: UmihiSettings,
        playlistId: String? = null,
        referrer: String? = null,
    ) {
        retryOnTransient(maxAttempts = 3) {
            sendWatchtimeUpdate(
                baseUrl = baseUrl, cpn = cpn, st = st, et = et,
                settings = settings, playlistId = playlistId, referrer = referrer,
            )
        }
    }

    private suspend fun sendWatchtimeCompleteWithRetry(
        baseUrl: String,
        cpn: String,
        durationSeconds: Float,
        settings: UmihiSettings,
        playlistId: String? = null,
        referrer: String? = null,
    ) {
        retryOnTransient(maxAttempts = 3) {
            sendWatchtimeComplete(
                baseUrl = baseUrl, cpn = cpn, durationSeconds = durationSeconds,
                settings = settings, playlistId = playlistId, referrer = referrer,
            )
        }
    }

    /** Retry a suspend block on transient HTTP failures (429/5xx) with exponential backoff. */
    private suspend fun retryOnTransient(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 1_000L,
        block: suspend () -> Int?,
    ) {
        var delayMs = initialDelayMs
        repeat(maxAttempts) { attempt ->
            val code = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.printe("PlaybackStats request failed: ${e.message}", exception = e)
                null
            }

            // Success (2xx) or non-retryable (4xx except 429) — stop
            if (code != null && code !in 429..599) {
                return
            }

            if (attempt < maxAttempts - 1) {
                LogHelper.printd("PlaybackStats transient failure (HTTP $code), retrying in ${delayMs}ms (attempt ${attempt + 1}/$maxAttempts)")
                delay(delayMs)
                delayMs *= 2
            }
        }
    }

    private suspend fun sendInitPlayback(
        baseUrl: String,
        cpn: String,
        settings: UmihiSettings,
        playlistId: String? = null,
        referrer: String? = null,
    ): Int? {
        if (!settings.canTrack) {
            LogHelper.printd("Playback stats request skipped: canTrack=false")
            return null
        }

        return withContext(Dispatchers.IO) {
            val url =
                buildUrl(baseUrl, cpn, playlistId, referrer)?.build() ?: return@withContext null
            val request = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .applyHeaders(url, settings)
                .build()

            executeRequest(request)
        }
    }

    private suspend fun sendWatchtimeUpdate(
        baseUrl: String,
        cpn: String,
        st: String,
        et: String,
        settings: UmihiSettings,
        playlistId: String? = null,
        referrer: String? = null,
    ): Int? {
        if (!settings.canTrack) {
            LogHelper.printd("Playback stats request skipped: canTrack=false")
            return null
        }

        return withContext(Dispatchers.IO) {
            val url = buildUrl(baseUrl, cpn, playlistId, referrer)
                ?.addEncodedQueryParameter("st", st)
                ?.addEncodedQueryParameter("et", et)
                ?.build()
                ?: return@withContext null

            val request = Request.Builder()
                .url(url)
                .post(FormBody.Builder().build())
                .applyHeaders(url, settings)
                .build()

            executeRequest(request)
        }
    }

    private suspend fun sendWatchtimeComplete(
        baseUrl: String,
        cpn: String,
        durationSeconds: Float,
        settings: UmihiSettings,
        playlistId: String? = null,
        referrer: String? = null,
    ): Int? {
        val durationStr = durationSeconds.formatDecimal()
        return sendWatchtimeUpdate(
            baseUrl = baseUrl,
            cpn = cpn,
            st = durationStr,
            et = durationStr,
            settings = settings,
            playlistId = playlistId,
            referrer = referrer,
        )
    }

    private fun buildUrl(
        baseUrl: String,
        cpn: String,
        playlistId: String?,
        referrer: String?,
    ): HttpUrl.Builder? {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder()
        if (builder == null) {
            LogHelper.printe("Invalid playback tracking URL: $baseUrl")
            return null
        }

        // Use setEncodedQueryParameter so we overwrite any value the tracking
        // URL already carries (e.g. its own "c" or "cpn") rather than adding
        // a duplicate that confuses YouTube's stats backend.
        builder.setEncodedQueryParameter("cpn", cpn)
        builder.setEncodedQueryParameter("ver", "2")
        builder.setEncodedQueryParameter("c", "WEB_REMIX")

        playlistId?.let { builder.setEncodedQueryParameter("list", it) }
        referrer?.let { builder.setEncodedQueryParameter("referrer", it) }

        return builder
    }


    private fun executeRequest(request: Request): Int? {
        return try {
            UmihiHttpClient.client.newCall(request).execute().use { response ->
                val queryPreview = (request.url.encodedQuery ?: "").take(80)
                LogHelper.printd("PlaybackStats: ${request.url.encodedPath}?$queryPreview... -> ${response.code}")
                response.code
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("PlaybackStats request failed: ${e.message}", exception = e)
            null
        }
    }
}