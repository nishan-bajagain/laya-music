package ca.ilianokokoro.umihi.music.data.repositories

import ca.ilianokokoro.umihi.music.models.LyricLine
import ca.ilianokokoro.umihi.music.models.LyricWord
import java.text.Normalizer
import java.util.LinkedHashSet
import kotlin.math.max

/**
 * The single parser and timing authority for the lyrics feature.
 *
 * Providers are allowed to return different envelopes, but once their text is
 * converted to LRC every caller uses this object. Keeping validation here
 * prevents one provider from ranking malformed timestamps as "synced" while a
 * later layer rejects them.
 */
object LyricsParser {

    private val timestampRegex =
        Regex("""\[(\d{1,4}):(\d{1,2})(?:[.,:](\d{1,3}))?]""")
    private val offsetRegex =
        Regex("""\[offset\s*:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val whitespaceRegex = Regex("""\s+""")

    fun parseLrc(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()

        // An offset tag can appear anywhere in the document, so read it in a
        // first pass instead of depending on provider tag ordering.
        val offsetMs = raw.lineSequence()
            .flatMap { line -> offsetRegex.findAll(line).asSequence() }
            .mapNotNull { it.groupValues.getOrNull(1)?.toLongOrNull() }
            .lastOrNull()
            ?: 0L

        val entries = LinkedHashSet<Pair<Long, String>>()
        raw.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trim()
            val matches = timestampRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach

            // Text starts after the last timestamp. This supports multiple
            // timestamps on one line without retaining timestamp syntax.
            val text = normalizeText(line.substring(matches.last().range.last + 1))
            if (text.isEmpty()) return@forEach

            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                if (seconds !in 0L..59L) return@forEach

                val fraction = match.groupValues.getOrNull(3).orEmpty()
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLongOrNull()?.times(100L)
                    2 -> fraction.toLongOrNull()?.times(10L)
                    else -> fraction.take(3).toLongOrNull()
                } ?: return@forEach

                val timestamp = minutes * 60_000L + seconds * 1_000L +
                    fractionMs + offsetMs
                if (timestamp >= 0L) entries += timestamp to text
            }
        }

        return normalizeTimed(entries.map { (timeMs, text) ->
            LyricLine(timeMs = timeMs, text = text)
        })
    }

    fun parsePlain(raw: String?): List<LyricLine> =
        raw.orEmpty()
            .lineSequence()
            .map { normalizeText(it) }
            .filter { it.isNotEmpty() }
            .map { LyricLine(timeMs = null, text = it) }
            .toList()

    /**
     * Sanitizes rich provider lines and infers missing end times from the next
     * cue. A synced document is valid only when every line has a non-negative
     * timestamp and non-empty text.
     */
    fun normalizeTimed(lines: List<LyricLine>): List<LyricLine> {
        val normalized = lines
            .asSequence()
            .mapNotNull { line ->
                val timeMs = line.timeMs?.takeIf { it >= 0L } ?: return@mapNotNull null
                val text = normalizeText(line.text)
                if (text.isEmpty()) return@mapNotNull null

                val words = line.words
                    ?.mapNotNull { word ->
                        val wordText = word.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val startMs = max(0L, word.startMs)
                        word.copy(
                            text = wordText,
                            startMs = startMs,
                            endMs = word.endMs?.takeIf { it > startMs }
                        )
                    }
                    ?.sortedBy { it.startMs }
                    ?.takeIf { it.isNotEmpty() }

                line.copy(timeMs = timeMs, text = text, words = words)
            }
            .sortedBy { it.timeMs }
            .toList()

        val deduplicated = LinkedHashSet<Pair<Long, String>>()
        val unique = normalized.filter { line ->
            deduplicated.add((line.timeMs ?: 0L) to line.text)
        }

        return unique.mapIndexed { index, line ->
            val startMs = line.timeMs ?: 0L
            val nextStart = unique.getOrNull(index + 1)?.timeMs
            val inferredEnd = nextStart?.takeIf { it > startMs }
            val endMs = line.endMs
                ?.takeIf { it > startMs }
                ?: inferredEnd
            val words = line.words?.mapIndexed { wordIndex, word ->
                val nextWordStart = line.words.getOrNull(wordIndex + 1)?.startMs
                word.copy(
                    endMs = word.endMs
                        ?: nextWordStart?.takeIf { it > word.startMs }
                        ?: endMs?.takeIf { it > word.startMs }
                )
            }
            line.copy(endMs = endMs, words = words)
        }
    }

    fun isUsableTimed(raw: String?): Boolean = parseLrc(raw).isNotEmpty()

    /**
     * Returns the last cue at or before [positionMs], or -1 before the first
     * cue. This is the one definition used by tests, ViewModel, and UI.
     */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        val timestamps = lines.mapNotNull { it.timeMs }
        if (timestamps.isEmpty() || positionMs < timestamps.first()) return -1

        var low = 0
        var high = timestamps.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (timestamps[middle] <= positionMs) low = middle else high = middle - 1
        }
        return low
    }

    fun normalizeMetadata(value: String): String =
        normalizeText(Normalizer.normalize(value, Normalizer.Form.NFKC))

    private fun normalizeText(value: String): String =
        whitespaceRegex.replace(value, " ").trim()
}