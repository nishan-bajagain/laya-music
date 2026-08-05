package ca.ilianokokoro.umihi.music.models

import kotlinx.serialization.Serializable

/**
 * A single lyric line with optional timing for synchronized display.
 * @param timeMs Playback position in milliseconds this line starts, or null for plain lyrics.
 * @param text The lyric text for this line.
 */
@Serializable
data class LyricLine(
    val timeMs: Long?,
    val text: String,
    /** Optional end time supplied by a richer timing payload. */
    val endMs: Long? = null,
    /** Optional word/syllable timing. LRC providers leave this null. */
    val words: List<LyricWord>? = null
)

/**
 * A word or syllable inside a timed lyric line.
 *
 * [endMs] is nullable because some upstream formats only provide a start
 * timestamp. The UI can still highlight the word until the next word or line.
 */
@Serializable
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long? = null
)
