package ca.ilianokokoro.umihi.music.data.repositories

import ca.ilianokokoro.umihi.music.models.LyricLine
import ca.ilianokokoro.umihi.music.models.LyricWord
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsParserTest {

    @Test
    fun parsesMultipleTimestampsAndNormalizesFractions() {
        val lines = LyricsParser.parseLrc(
            """
            [00:01.5][00:02.50]  hello   world
            [00:03:500]next
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LyricLine(timeMs = 1_500L, text = "hello world", endMs = 2_500L),
                LyricLine(timeMs = 2_500L, text = "hello world", endMs = 3_500L),
                LyricLine(timeMs = 3_500L, text = "next")
            ),
            lines
        )
    }

    @Test
    fun appliesPositiveAndNegativeOffsetsAndRejectsNegativeResults() {
        assertEquals(
            1_500L,
            LyricsParser.parseLrc("[offset:500]\n[00:01.00]line").single().timeMs
        )
        assertTrue(
            LyricsParser.parseLrc("[offset:-2000]\n[00:01.00]line").isEmpty()
        )
    }

    @Test
    fun malformedTimestampsDoNotHideValidLines() {
        val lines = LyricsParser.parseLrc(
            """
            [00:99.00]bad seconds
            [oops]bad tag
            [00:04.00]valid
            [ar:artist]
            """.trimIndent()
        )

        assertEquals(1, lines.size)
        assertEquals(4_000L, lines.single().timeMs)
        assertEquals("valid", lines.single().text)
    }

    @Test
    fun preservesDifferentTextsAtSameTimestampButRemovesExactDuplicates() {
        val lines = LyricsParser.parseLrc(
            """
            [00:01.00]one
            [00:01.00]one
            [00:01.00]translation
            """.trimIndent()
        )

        assertEquals(
            listOf(
                LyricLine(timeMs = 1_000L, text = "one", endMs = null),
                LyricLine(timeMs = 1_000L, text = "translation")
            ),
            lines
        )
    }

    @Test
    fun currentIndexUsesLastCueAtOrBeforePosition() {
        val lines = listOf(
            LyricLine(1_000L, "first"),
            LyricLine(2_000L, "second"),
            LyricLine(5_000L, "third")
        )

        assertEquals(-1, LyricsParser.currentIndex(lines, 999L))
        assertEquals(0, LyricsParser.currentIndex(lines, 1_000L))
        assertEquals(0, LyricsParser.currentIndex(lines, 1_999L))
        assertEquals(1, LyricsParser.currentIndex(lines, 2_000L))
        assertEquals(2, LyricsParser.currentIndex(lines, 99_000L))
    }

    @Test
    fun normalizesRichLinesAndInfersLineAndWordEnds() {
        val lines = LyricsParser.normalizeTimed(
            listOf(
                LyricLine(
                    timeMs = 2_000L,
                    text = " second ",
                    words = listOf(LyricWord("two", 2_200L))
                ),
                LyricLine(
                    timeMs = 1_000L,
                    text = " first ",
                    words = listOf(
                        LyricWord("one", 1_100L),
                        LyricWord("start", 1_400L)
                    )
                )
            )
        )

        assertEquals("first", lines[0].text)
        assertEquals(2_000L, lines[0].endMs)
        assertEquals(1_400L, lines[0].words?.get(0)?.endMs)
        assertEquals(2_000L, lines[0].words?.get(1)?.endMs)
        assertEquals(2_000L, lines[1].timeMs)
        assertEquals("second", lines[1].text)
    }
}