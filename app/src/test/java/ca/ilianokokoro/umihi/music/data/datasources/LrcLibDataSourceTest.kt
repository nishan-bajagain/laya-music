package ca.ilianokokoro.umihi.music.data.datasources

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcLibDataSourceTest {

    private val source = LrcLibDataSource()

    @Test
    fun classifiesNotFoundRateLimitServerAndSuccessStatuses() {
        assertIs<LrcLibDataSource.Outcome.NotFound>(source.classifyHttpStatus(404))
        assertIs<LrcLibDataSource.Outcome.RateLimited>(source.classifyHttpStatus(429))
        assertIs<LrcLibDataSource.Outcome.ServerError>(source.classifyHttpStatus(500))
        assertIs<LrcLibDataSource.Outcome.ServerError>(source.classifyHttpStatus(503))
        assertNull(source.classifyHttpStatus(200))
    }

    @Test
    fun decodesSearchArrayAndObjectResponses() {
        val array = source.decodeSearchEntriesForTest(
            """
            [
              {
                "id": 1,
                "trackName": "Array Song",
                "artistName": "Artist",
                "duration": 180.0,
                "syncedLyrics": "[00:01.00]hello"
              }
            ]
            """.trimIndent()
        )
        val objectResult = source.decodeSearchEntriesForTest(
            """
            {
              "id": 2,
              "trackName": "Object Song",
              "artistName": "Artist",
              "duration": 181.0,
              "plainLyrics": "hello"
            }
            """.trimIndent()
        )

        assertEquals("Array Song", array.single().trackName)
        assertTrue(array.single().hasSynced())
        assertEquals("Object Song", objectResult.single().trackName)
        assertTrue(objectResult.single().hasPlain())
    }

    @Test
    fun lookupPhasesKeepCachedGetAndSearchOrder() {
        assertEquals(
            listOf("get-cached", "get", "search"),
            LrcLibDataSource.LOOKUP_PHASES
        )
    }

    @Test
    fun metadataBeatsDurationAndWrongArtistIsRejected() {
        val selected = source.selectBestSearchEntryForTest(
            entries = listOf(
                LrcLibDataSource.Track(
                    id = 1,
                    trackName = "Same Song",
                    artistName = "Wrong Artist",
                    albumName = "Other Album",
                    durationSeconds = 180,
                    instrumental = false,
                    plainLyrics = null,
                    syncedLyrics = "[00:01.00]wrong"
                ),
                LrcLibDataSource.Track(
                    id = 2,
                    trackName = "Same Song",
                    artistName = "Right Artist",
                    albumName = "Right Album",
                    durationSeconds = 195,
                    instrumental = false,
                    plainLyrics = null,
                    syncedLyrics = "[00:01.00]right"
                )
            ),
            title = "Same Song",
            artist = "Right Artist",
            album = "Right Album",
            durationSeconds = 180
        )

        assertEquals(2, selected?.id)
    }
}