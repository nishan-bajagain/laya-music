package ca.ilianokokoro.umihi.music.data.datasources

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YtmPlainLyricsDataSourceTest {

    private val source = YtmPlainLyricsDataSource()

    @Test
    fun extractsPlainLyricsTextButIgnoresTimedCueModels() {
        val plain = source.extractPlainLyricsForTest(
            """
            {
              "contents": {
                "musicDescriptionShelfRenderer": {
                  "description": {
                    "runs": [{"text": "first line"}, {"text": "\nsecond line"}]
                  }
                },
                "timedLyricsModel": {
                  "cueGroups": [{"cues": [{"startTimeMs": "0", "text": "do not use"}]}]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("first line\nsecond line", plain)
        assertNull(
            source.extractPlainLyricsForTest(
                """{"timedLyricsModel":{"lyrics":"timed only"}}"""
            )
        )
    }
}