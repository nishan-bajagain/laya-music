package ca.ilianokokoro.umihi.music.ui.screens.lyrics

import android.app.Application
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.LyricsRepository
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val datastoreRepository = DatastoreRepository(application)

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var trackingJob: Job? = null
    private var autoScrollResumeJob: Job? = null
    private var songObserverJob: Job? = null

    private var lastLoadedSongId: String? = null

    init {
        // Load persisted global offset immediately
        viewModelScope.launch {
            val savedOffset = datastoreRepository.getLyricsOffset()
            _uiState.update { it.copy(offsetMs = savedOffset) }
        }

        // Observe controller — restart song-change observer on each reconnect
        viewModelScope.launch {
            PlayerManager.controllerState.collect { controller ->
                if (controller != null) {
                    songObserverJob?.cancel()
                    songObserverJob = observeSongChanges()
                }
            }
        }

        startPositionTracking()
    }

    /**
     * Polls for track changes with a 300 ms debounce to absorb rapid skip spam.
     * Each iteration waits 1 s to detect a new song; if a new song is detected it
     * waits an additional 300 ms (debounce) before firing the lyrics request so that
     * consecutive skips don't each trigger a full fetch.
     */
    private fun observeSongChanges(): Job {
        return viewModelScope.launch {
            var pendingSongId: String? = null
            while (isActive) {
                val song = withContext(Dispatchers.Main.immediate) {
                    PlayerManager.getCurrentSong()
                }

                if (song != null && song.youtubeId != lastLoadedSongId) {
                    if (song.youtubeId != pendingSongId) {
                        // New candidate — start debounce window
                        pendingSongId = song.youtubeId
                        delay(Constants.Lyrics.SONG_CHANGE_DEBOUNCE_MS)
                    } else {
                        // Same candidate survived the debounce — fire the request
                        pendingSongId = null
                        lastLoadedSongId = song.youtubeId
                        loadLyrics(song)
                        // Also cancel any stale prefetch from the previous track
                        prefetchJob?.cancel()
                    }
                } else {
                    pendingSongId = null
                    delay(1_000L)
                }
            }
        }
    }

    /**
     * Called when the lyrics sheet opens — loads lyrics for the current song
     * immediately rather than waiting for the polling loop.
     */
    fun loadLyricsForCurrentSong() {
        val song = PlayerManager.getCurrentSong() ?: return
        val currentState = _uiState.value.screenState
        val shouldLoad = song.youtubeId != lastLoadedSongId
                || currentState is LyricsScreenState.NotFound
                || currentState is LyricsScreenState.Error
                || currentState is LyricsScreenState.Offline
        if (shouldLoad) {
            lastLoadedSongId = song.youtubeId
            loadLyrics(song)
        }
    }

    private fun loadLyrics(song: Song, forceRefresh: Boolean = false) {
        loadJob?.cancel()

        // Fast path: check in-process memory cache before touching Room or network
        if (!forceRefresh) {
            val memResult = LyricsRepository.peekMemoryCache(song.youtubeId)
            if (memResult != null) {
                _uiState.update {
                    it.copy(
                        screenState = memResult.toScreenState(),
                        autoScrollEnabled = true
                    )
                }
                schedulePrefetch(song)
                return
            }
        }

        // Start with "checking cache" state — Room lookup is fast
        _uiState.update { it.copy(screenState = LyricsScreenState.LoadingCache, autoScrollEnabled = true) }

        loadJob = viewModelScope.launch {
            try {
                val durationSec = parseDurationSeconds(song.duration)
                val result = lyricsRepository.getLyrics(
                    videoId = song.youtubeId,
                    title = song.title,
                    artist = song.artist,
                    durationSeconds = durationSec,
                    forceRefresh = forceRefresh,
                    onNetworkFetch = {
                        // Cache was missed — we're now fetching from the network
                        _uiState.update { it.copy(screenState = LyricsScreenState.LoadingSynced) }
                    }
                )
                _uiState.update { it.copy(screenState = result.toScreenState()) }
                schedulePrefetch(song)
            } catch (e: Exception) {
                LogHelper.printe("LyricsViewModel load failed: ${e.message}", exception = e)
                _uiState.update {
                    it.copy(screenState = LyricsScreenState.Error("${e.message}", retryable = true))
                }
            }
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
        lastLoadedSongId = null
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

    /** Re-enable auto-scroll immediately (e.g. user taps a lyric line). */
    fun resumeAutoScroll() {
        autoScrollResumeJob?.cancel()
        _uiState.update { it.copy(autoScrollEnabled = true) }
    }

    /**
     * Nudge the global lyrics timing offset by [deltaMs] (clamped to ±5 000 ms).
     * The new value is persisted to DataStore so it survives app restarts.
     */
    fun adjustOffset(deltaMs: Long) {
        val newOffset = (_uiState.value.offsetMs + deltaMs)
            .coerceIn(Constants.Lyrics.OFFSET_MIN_MS, Constants.Lyrics.OFFSET_MAX_MS)
        _uiState.update { it.copy(offsetMs = newOffset) }
        viewModelScope.launch {
            datastoreRepository.saveLyricsOffset(newOffset)
        }
    }

    /** Reset offset to zero. */
    fun resetOffset() {
        _uiState.update { it.copy(offsetMs = 0L) }
        viewModelScope.launch {
            datastoreRepository.saveLyricsOffset(0L)
        }
    }

    private fun startPositionTracking() {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            while (isActive) {
                val posMs = withContext(Dispatchers.Main.immediate) {
                    PlayerManager.currentController?.currentPosition ?: 0L
                }
                val offset = _uiState.value.offsetMs
                val adjustedPos = posMs + offset

                _uiState.update { state ->
                    val screenState = state.screenState
                    if (screenState is LyricsScreenState.Synced) {
                        val idx = findCurrentLineIndex(
                            screenState.lines.map { it.timeMs ?: 0L },
                            adjustedPos
                        )
                        state.copy(
                            screenState = if (idx != screenState.currentIndex)
                                screenState.copy(currentIndex = idx)
                            else screenState,
                            positionMs = adjustedPos
                        )
                    } else {
                        state.copy(positionMs = adjustedPos)
                    }
                }

                delay(Constants.Player.PROGRESS_UPDATE_DELAY.toLong())
            }
        }
    }

    /**
     * Binary search for the index of the currently playing lyric line.
     * Returns -1 if playback is before the first lyric (no line highlighted).
     */
    private fun findCurrentLineIndex(timestamps: List<Long>, positionMs: Long): Int {
        if (timestamps.isEmpty()) return -1
        if (positionMs < timestamps.first()) return -1
        var lo = 0
        var hi = timestamps.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timestamps[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, timestamps.size - 1)
    }

    private fun parseDurationSeconds(duration: String): Int? {
        return try {
            val parts = duration.trim().split(":").map { it.toInt() }
            when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        prefetchJob?.cancel()
        trackingJob?.cancel()
        autoScrollResumeJob?.cancel()
        songObserverJob?.cancel()
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { LyricsViewModel(application) }
        }
    }
}

// ── Extension: LyricsResult → LyricsScreenState ──────────────────────────────

private fun LyricsRepository.LyricsResult.toScreenState(): LyricsScreenState = when (this) {
    is LyricsRepository.LyricsResult.Synced -> LyricsScreenState.Synced(lines = lines, provider = provider)
    is LyricsRepository.LyricsResult.Plain  -> LyricsScreenState.Plain(lines = lines, provider = provider)
    LyricsRepository.LyricsResult.NotFound   -> LyricsScreenState.NotFound
    LyricsRepository.LyricsResult.Instrumental -> LyricsScreenState.Instrumental
    LyricsRepository.LyricsResult.Offline    -> LyricsScreenState.Offline
}
