package ca.ilianokokoro.umihi.music.ui.screens.lyrics

import ca.ilianokokoro.umihi.music.models.LyricLine

sealed class LyricsScreenState {
    /** Checking the local Room cache — typically very fast. */
    data object LoadingCache : LyricsScreenState()

    /** Cache miss; fetching synced lyrics from the network. */
    data object LoadingSynced : LyricsScreenState()

    /** Lyrics fetched and ready for synced display. */
    data class Synced(
        val lines: List<LyricLine>,
        val currentIndex: Int = -1,
        val provider: String = ""
    ) : LyricsScreenState()

    /** Plain (unsynced) lyrics displayed as a scrollable list. */
    data class Plain(
        val lines: List<LyricLine>,
        val provider: String = ""
    ) : LyricsScreenState()

    /** Track is instrumental — no lyrics exist by design. */
    data object Instrumental : LyricsScreenState()

    /** No lyrics found across all providers. */
    data object NotFound : LyricsScreenState()

    /** A provider could not be reached and no cached result was usable. */
    data class NetworkError(val message: String) : LyricsScreenState()

    /** LRCLIB explicitly asked the client to slow down. */
    data object RateLimited : LyricsScreenState()

    /** A provider returned a response that did not match its schema. */
    data class Unknown(val message: String) : LyricsScreenState()

    /** Unexpected local/runtime failure, retained as a separate retryable state. */
    data class Error(val message: String, val retryable: Boolean = true) : LyricsScreenState()
}

data class LyricsUiState(
    val screenState: LyricsScreenState = LyricsScreenState.LoadingCache,
    /** Whether auto-scroll is currently active (false when user scrolls manually). */
    val autoScrollEnabled: Boolean = true,
    /** Exact Media3 playback position in milliseconds, used for line highlighting. */
    val positionMs: Long = 0L
)
