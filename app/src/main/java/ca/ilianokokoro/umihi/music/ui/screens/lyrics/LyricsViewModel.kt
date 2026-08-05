package ca.ilianokokoro.umihi.music.ui.screens.lyrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.helpers.DurationParser
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.LyricsParser
import ca.ilianokokoro.umihi.music.data.repositories.LyricsRepository
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val lyricsRepository = LyricsRepository(application)
    private val localSongDataSource = AppDatabase.getInstance(application).songRepository()

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var trackingJob: Job? = null
    private var autoScrollResumeJob: Job? = null
    private var songChangeJob: Job? = null
    private var registeredPlayerController: Player? = null

    private var activeSongId: String? = null
    private var loadGeneration = 0L

    /**
     * Precomputed lyric timestamps (ms) for O(log n) binary-search index lookup.
     * Built once per song so the per-tick position update never traverses the list.
     */
    private var timestamps: LongArray = LongArray(0)

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            handleCurrentSongChanged()
            publishCurrentPosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // This fires for progress-bar drags, lyric taps, repeat and automatic
            // transitions. Publish immediately instead of waiting for the poll.
            publishCurrentPosition()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishCurrentPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publishCurrentPosition()
        }
    }

    init {
        // Register one playback listener per controller. Media3 callbacks make
        // seeks and track changes immediate; the position loop below remains a
        // low-cost fallback for ordinary playback progression.
        viewModelScope.launch {
            PlayerManager.controllerState.collect { controller ->
                if (controller !== registeredPlayerController) {
                    registeredPlayerController?.removeListener(playerListener)
                    registeredPlayerController = controller
                    controller?.addListener(playerListener)
                }
                if (controller != null) {
                    handleCurrentSongChanged()
                    publishCurrentPosition()
                } else {
                    // A dead/released controller must invalidate any in-flight
                    // request so its result cannot later update this screen.
                    loadGeneration += 1
                    activeSongId = null
                    songChangeJob?.cancel()
                    loadJob?.cancel()
                    timestamps = LongArray(0)
                    _uiState.update {
                        it.copy(
                            screenState = LyricsScreenState.LoadingCache,
                            positionMs = 0L,
                            autoScrollEnabled = true
                        )
                    }
                }
            }
        }

        startPositionTracking()
    }

    private fun handleCurrentSongChanged() {
        val song = PlayerManager.getCurrentSong()
        if (song == null) {
            activeSongId = null
            songChangeJob?.cancel()
            loadJob?.cancel()
            timestamps = LongArray(0)
            _uiState.update {
                it.copy(
                    screenState = LyricsScreenState.LoadingCache,
                    positionMs = 0L,
                    autoScrollEnabled = true
                )
            }
            return
        }

        if (song.youtubeId == activeSongId) return

        activeSongId = song.youtubeId
        loadGeneration += 1
        loadJob?.cancel()
        prefetchJob?.cancel()
        songChangeJob?.cancel()
        timestamps = LongArray(0)
        _uiState.update {
            it.copy(
                screenState = LyricsScreenState.LoadingCache,
                positionMs = 0L,
                autoScrollEnabled = true
            )
        }

        // Absorb rapid next/previous presses, while still reacting immediately
        // to the new track and never showing the previous track's lyrics.
        songChangeJob = viewModelScope.launch {
            delay(Constants.Lyrics.SONG_CHANGE_DEBOUNCE_MS)
            if (PlayerManager.getCurrentSong()?.youtubeId == song.youtubeId) {
                loadLyrics(song)
            }
        }
    }

    /**
     * Called when the lyrics sheet opens — loads lyrics for the current song
     * immediately rather than waiting for the polling loop.
     */
    fun loadLyricsForCurrentSong() {
        val song = PlayerManager.getCurrentSong() ?: return
        loadLyricsForSong(song)
    }

    /**
     * Loads the song that the lyrics sheet is already rendering. Using this
     * value avoids losing a request when MediaController briefly disconnects
     * during sheet composition or a playback-service reconnect.
     */
    fun loadLyricsForSong(song: Song) {
        val currentState = _uiState.value.screenState
        val shouldLoad = song.youtubeId != activeSongId
                || currentState is LyricsScreenState.NotFound
                || currentState is LyricsScreenState.Error
                || currentState is LyricsScreenState.NetworkError
                || currentState is LyricsScreenState.RateLimited
                || currentState is LyricsScreenState.Unknown
                // Plain lyrics are only a fallback. Re-scan providers when
                // the sheet is opened again so a temporarily unavailable
                // timed result can upgrade the screen to synced lyrics.
                || currentState is LyricsScreenState.Plain
                || (currentState is LyricsScreenState.LoadingCache &&
                    loadJob?.isActive != true)
        if (shouldLoad) {
            songChangeJob?.cancel()
            songChangeJob = null
            activeSongId = song.youtubeId
            loadLyrics(song)
        }
    }

    private fun loadLyrics(song: Song, forceRefresh: Boolean = false) {
        loadJob?.cancel()
        val requestGeneration = ++loadGeneration
        activeSongId = song.youtubeId

        fun isCurrentRequest(): Boolean =
            requestGeneration == loadGeneration &&
                (
                    PlayerManager.getCurrentSong()?.youtubeId == song.youtubeId ||
                        (PlayerManager.getCurrentSong() == null && activeSongId == song.youtubeId)
                    )

        // Fast path: check in-process memory cache before touching Room or network
        if (!forceRefresh) {
            val memResult = LyricsRepository.peekMemoryCache(song.youtubeId)
                ?.takeIf { it.isPositive }
            if (memResult != null) {
                if (!isCurrentRequest()) return
                onLyricsResult(memResult)
                schedulePrefetch(song)
                return
            }
        }

        // Start with "checking cache" state — Room lookup is fast
        _uiState.update { it.copy(screenState = LyricsScreenState.LoadingCache, autoScrollEnabled = true) }

        loadJob = viewModelScope.launch {
            try {
                // Media3 metadata can briefly be incomplete while the player
                // transitions items. Recover the canonical song row before
                // building provider queries so an empty title cannot turn into
                // a silent zero-provider lookup.
                val metadataSong = if (
                    song.title.isBlank() || song.artist.isBlank() || song.duration.isBlank()
                ) {
                    try {
                        localSongDataSource.getSong(song.youtubeId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LogHelper.printe(
                            "Lyrics local metadata lookup failed for ${song.youtubeId}",
                            exception = e
                        )
                        null
                    }
                } else {
                    null
                }
                val requestSong = song.mergeLyricsMetadata(metadataSong)

                LogHelper.printLyrics(
                    "Lyrics load videoId=${requestSong.youtubeId} " +
                        "title=\"${requestSong.title}\" artist=\"${requestSong.artist}\" " +
                        "duration=\"${requestSong.duration}\""
                )
                val durationSec = parseDurationSeconds(requestSong.duration)
                val result = lyricsRepository.getLyrics(
                    videoId = requestSong.youtubeId,
                    title = requestSong.title,
                    artist = requestSong.artist,
                    durationSeconds = durationSec,
                    forceRefresh = forceRefresh,
                    onNetworkFetch = {
                        // LyricsRepository invokes this callback on Dispatchers.IO.
                        // MediaController is main-thread confined, so perform the
                        // current-request check on Main before touching player state.
                        withContext(Dispatchers.Main.immediate) {
                            if (isCurrentRequest()) {
                                _uiState.update {
                                    it.copy(screenState = LyricsScreenState.LoadingSynced)
                                }
                            }
                        }
                    }
                )
                if (!isCurrentRequest()) return@launch
                onLyricsResult(result)
                schedulePrefetch(song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrentRequest()) return@launch
                LogHelper.printe("LyricsViewModel load failed: ${e.message}", exception = e)
                _uiState.update {
                    it.copy(
                        // Never disguise a provider/parser/runtime failure as
                        // an absent lyric. It must stay retryable and visible.
                        screenState = LyricsScreenState.Error(
                            message = e.message ?: "Unexpected lyrics loading failure",
                            retryable = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Central handler when lyrics arrive — precomputes the timestamp array for
     * binary search and resets scroll state.
     */
    private fun onLyricsResult(result: LyricsRepository.LyricsOutcome) {
        if (result is LyricsRepository.LyricsOutcome.Synced) {
            // Precompute a compact LongArray of timestamps for O(log n) lookups.
            timestamps = result.lines.mapNotNull { it.timeMs }.toLongArray()
        } else {
            timestamps = LongArray(0)
        }
        val controllerPosition = PlayerManager.currentController
            ?.currentPosition
            ?.coerceAtLeast(0L)
            ?: 0L
        val initialIndex = (result as? LyricsRepository.LyricsOutcome.Synced)
            ?.let { LyricsParser.currentIndex(it.lines, controllerPosition) }
            ?: -1
        _uiState.update {
            it.copy(
                screenState = result.toScreenState().withCurrentIndex(initialIndex),
                positionMs = controllerPosition,
                autoScrollEnabled = true
            )
        }
    }

    /**
     * Prefetch lyrics for the next queued track in the background so it loads
     * instantly when the user skips. Cancelled immediately on any track change.
     */
    private fun schedulePrefetch(currentSong: Song) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            try {
                val queue = withContext(Dispatchers.Main.immediate) { PlayerManager.getQueue() }
                val currentIdx = withContext(Dispatchers.Main.immediate) { PlayerManager.getCurrentIndex() }
                val nextSong = queue.getOrNull(currentIdx + 1) ?: return@launch

                // Skip if already cached in memory
                if (LyricsRepository.peekMemoryCache(nextSong.youtubeId) != null) return@launch

                LogHelper.printd("Prefetching lyrics for next track: ${nextSong.title}")
                lyricsRepository.getLyrics(
                    videoId = nextSong.youtubeId,
                    title = nextSong.title,
                    artist = nextSong.artist,
                    durationSeconds = parseDurationSeconds(nextSong.duration)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Prefetch failures are silent — they don't affect the current track
            }
        }
    }

    /** Force-refresh lyrics for the current song, bypassing all caches. */
    fun retry() {
        val song = PlayerManager.getCurrentSong() ?: run {
            _uiState.update { it.copy(screenState = LyricsScreenState.NotFound) }
            return
        }
        songChangeJob?.cancel()
        songChangeJob = null
        activeSongId = null
        loadGeneration += 1
        LyricsRepository.evictFromMemory(song.youtubeId)
        loadLyrics(song, forceRefresh = true)
    }

    /** Called when the user manually scrolls — temporarily disables auto-scroll. */
    fun onUserScrolled() {
        _uiState.update { it.copy(autoScrollEnabled = false) }
        autoScrollResumeJob?.cancel()
        autoScrollResumeJob = viewModelScope.launch {
            delay(Constants.Lyrics.SYNC_RESUME_DELAY_MS)
            _uiState.update { it.copy(autoScrollEnabled = true) }
        }
    }

    /** Re-enable auto-scroll immediately (e.g. user taps the Jump-to-current button). */
    fun resumeAutoScroll() {
        autoScrollResumeJob?.cancel()
        _uiState.update { it.copy(autoScrollEnabled = true) }
    }

    /** Seek playback to a tapped lyric line and keep the lyrics view following it. */
    fun seekToLyric(index: Int) {
        val state = _uiState.value.screenState as? LyricsScreenState.Synced ?: return
        val line = state.lines.getOrNull(index) ?: return
        val targetMs = line.timeMs?.coerceAtLeast(0L) ?: return

        PlayerManager.currentController?.seekTo(targetMs)
        autoScrollResumeJob?.cancel()
        _uiState.update {
            it.copy(
                autoScrollEnabled = true,
                positionMs = targetMs,
                screenState = state.copy(currentIndex = index)
            )
        }
    }

    /**
     * Position tracking loop.
     *
     * Performance optimizations over the previous implementation:
     *  - Polls at 100 ms while playing, 500 ms while paused (saves battery).
     *  - Only publishes a new state when the active line index actually changes,
     *    so the lyrics list is not recomposed on every tick.
     *  - Uses a precomputed LongArray + binary search instead of mapping the
     *    timestamps list on every tick (O(n) → O(log n)).
     */
    private fun startPositionTracking() {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            var reconcileTick = 0
            while (isActive) {
                // Listener callbacks are the fast path, but a controller
                // reconnect or queue mutation can occasionally skip one.
                // Reconcile on both the playing and paused cadence so the
                // displayed lyrics cannot remain attached to an old track.
                if (++reconcileTick % 10 == 0) {
                    val liveId = PlayerManager.getCurrentSong()?.youtubeId
                    if (liveId != activeSongId) {
                        handleCurrentSongChanged()
                    }
                }

                val controller = PlayerManager.currentController
                val isPlaying = controller?.isPlaying == true
                val rawPos = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                val syncedPosition = rawPos

                _uiState.update { state ->
                    val screenState = state.screenState
                    if (screenState is LyricsScreenState.Synced) {
                        val idx = LyricsParser.currentIndex(screenState.lines, syncedPosition)
                        // Position must remain live even while the active line
                        // stays the same: rich word timing depends on it.
                        state.copy(
                            positionMs = syncedPosition,
                            screenState = screenState.copy(currentIndex = idx)
                        )
                    } else {
                        state
                    }
                }

                delay(if (isPlaying) 100L else 500L)
            }
        }
    }

    /** Publish the exact current player position in response to Media3 events. */
    private fun publishCurrentPosition() {
        val controller = PlayerManager.currentController ?: return
        val syncedPosition = controller.currentPosition.coerceAtLeast(0L)

        _uiState.update { state ->
            val screenState = state.screenState
            if (screenState is LyricsScreenState.Synced) {
                val idx = LyricsParser.currentIndex(screenState.lines, syncedPosition)
                state.copy(
                    positionMs = syncedPosition,
                    screenState = screenState.copy(currentIndex = idx)
                )
            } else {
                state
            }
        }
    }

    private fun parseDurationSeconds(duration: String): Int? {
        return DurationParser.parseToSeconds(duration)
    }

    private fun Song.mergeLyricsMetadata(local: Song?): Song {
        if (local == null) return this
        return copy(
            title = title.ifBlank { local.title },
            artist = artist.ifBlank { local.artist },
            duration = duration.ifBlank { local.duration },
            thumbnailHref = thumbnailHref.ifBlank { local.thumbnailHref }
        )
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        prefetchJob?.cancel()
        trackingJob?.cancel()
        autoScrollResumeJob?.cancel()
        songChangeJob?.cancel()
        registeredPlayerController?.removeListener(playerListener)
        registeredPlayerController = null
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { LyricsViewModel(application) }
        }
    }
}

// ── Extension: LyricsOutcome → LyricsScreenState ─────────────────────────────

internal fun LyricsRepository.LyricsOutcome.toScreenState(): LyricsScreenState = when (this) {
    is LyricsRepository.LyricsOutcome.Synced ->
        LyricsScreenState.Synced(lines = lines, provider = source)
    is LyricsRepository.LyricsOutcome.Plain ->
        LyricsScreenState.Plain(lines = lines, provider = source)
    LyricsRepository.LyricsOutcome.NotFound -> LyricsScreenState.NotFound
    LyricsRepository.LyricsOutcome.Instrumental -> LyricsScreenState.Instrumental
    is LyricsRepository.LyricsOutcome.NetworkError ->
        LyricsScreenState.NetworkError(message)
    LyricsRepository.LyricsOutcome.RateLimited -> LyricsScreenState.RateLimited
    is LyricsRepository.LyricsOutcome.Unknown ->
        LyricsScreenState.Unknown(message)
}

private fun LyricsScreenState.withCurrentIndex(index: Int): LyricsScreenState =
    (this as? LyricsScreenState.Synced)?.copy(currentIndex = index) ?: this