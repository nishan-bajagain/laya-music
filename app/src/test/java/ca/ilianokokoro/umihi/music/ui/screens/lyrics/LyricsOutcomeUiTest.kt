package ca.ilianokokoro.umihi.music.ui.screens.lyrics

import ca.ilianokokoro.umihi.music.data.repositories.LyricsRepository
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LyricsOutcomeUiTest {

    @Test
    fun networkAndRateLimitOutcomesRemainDistinctInTheUi() {
        val network = LyricsRepository.LyricsOutcome.NetworkError("offline")
            .toScreenState()
        val rateLimited = LyricsRepository.LyricsOutcome.RateLimited
            .toScreenState()

        assertIs<LyricsScreenState.NetworkError>(network)
        assertEquals("offline", (network as LyricsScreenState.NetworkError).message)
        assertIs<LyricsScreenState.RateLimited>(rateLimited)
    }

    @Test
    fun malformedProviderResponseMapsToUnknownNotNotFound() {
        val state = LyricsRepository.LyricsOutcome.Unknown("bad response")
            .toScreenState()

        assertIs<LyricsScreenState.Unknown>(state)
        assertEquals("bad response", (state as LyricsScreenState.Unknown).message)
    }
}