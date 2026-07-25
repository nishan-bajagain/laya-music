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

    /** Device is offline and there is no cached result for this track. */
    data object Offline : LyricsScreenState()

    /** A recoverable error occurred (network/parse). [retryable] controls whether to show Retry. */
    data class Error(val message: String, val retryable: Boolean = true) : LyricsScreenState()
}

data class LyricsUiState(
    val screenState: LyricsScreenState = LyricsScreenState.LoadingCache,
    /** Whether auto-scroll is currently active (false when user scrolls manually). */
    val autoScrollEnabled: Boolean = true,
    /** Playback position in milliseconds, used for line highlighting. */
    val positionMs: Long = 0L,
    /** Global offset (ms) to advance or delay lyric timing to match playback. */
    val offsetMs: Long = 0L
)
