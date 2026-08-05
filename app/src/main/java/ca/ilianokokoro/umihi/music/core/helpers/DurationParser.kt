package ca.ilianokokoro.umihi.music.core.helpers

/**
 * Shared utility for parsing duration strings in "mm:ss" or "hh:mm:ss" format.
 *
 * Previously this logic was duplicated in:
 *  - LyricsViewModel.parseDurationSeconds()
 *  - SongDownloadWorker.parseDurationToSeconds()
 *  - PlaylistDownloadWorker (inline)
 *
 * Centralising it here eliminates the duplication and ensures consistent
 * error handling across all call sites.
 */
object DurationParser {

    /**
     * Parse a duration string like "3:45" or "1:02:30" into total seconds.
     * @return total seconds, or null if the string is malformed.
     */
    fun parseToSeconds(duration: String): Int? {
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

    /**
     * Format a duration in seconds to a display string.
     * - < 1 hour: "m:ss"
     * - >= 1 hour: "h:mm:ss"
     */
    fun formatFromSeconds(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}