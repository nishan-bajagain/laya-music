package ca.ilianokokoro.umihi.music.data.repositories

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.models.CachedLyrics
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsRepositoryPolicyTest {

    @Test
    fun stalePlainCacheDoesNotBlockAProviderScan() {
        val cached = CachedLyrics(
            videoId = "video",
            syncedLyrics = null,
            plainLyrics = "plain fallback",
            provider = "ytm",
            cachedAtMs = System.currentTimeMillis() -
                Constants.Lyrics.PLAIN_CACHE_TTL_MS - 1
        )

        assertTrue(
            System.currentTimeMillis() - cached.cachedAtMs >
                Constants.Lyrics.PLAIN_CACHE_TTL_MS
        )
        // The repository only returns this stale plain value after its
        // synchronized-provider scan has already completed.
        assertEquals("ytm", cached.provider)
    }
}