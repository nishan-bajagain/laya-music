package ca.ilianokokoro.umihi.music.models

/**
 * A single lyric line with optional timing for synchronized display.
 * @param timeMs Playback position in milliseconds this line starts, or null for plain lyrics.
 * @param text The lyric text for this line.
 */
data class LyricLine(
    val timeMs: Long?,
    val text: String
)
