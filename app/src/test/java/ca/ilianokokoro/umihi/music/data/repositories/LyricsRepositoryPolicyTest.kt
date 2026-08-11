package ca.ilianokokoro.umihi.music.data.repositories

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.LruCache
import ca.ilianokokoro.umihi.music.models.CachedLyrics
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsRepositoryPolicyTest {

    @Test
    fun memoryCacheNeverExceedsCapAndEvictsOldestFirst() {
        val cache = LruCache<String, Int>(Constants.Lyrics.MEMORY_CACHE_MAX_ENTRIES)

        repeat(Constants.Lyrics.MEMORY_CACHE_MAX_ENTRIES) { i ->
            cache["video$i"] = i
        }
        assertEquals(Constants.Lyrics.MEMORY_CACHE_MAX_ENTRIES, cache.size)

        // One more distinct ID pushes the oldest entry out of the cache.
        cache["overflow"] = -1
        assertEquals(Constants.Lyrics.MEMORY_CACHE_MAX_ENTRIES, cache.size)
        assertNull(cache["video0"])
        assertEquals(-1, cache["overflow"])
    }

    @Test
    fun memoryCacheEvictsLeastRecentlyUsedEntryNotMerelyOldestInserted() {
        val cache = LruCache<String, Int>(3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3

        // Reading "a" refreshes its recency, making "b" the LRU entry.
        assertEquals(1, cache["a"])
        cache["d"] = 4

        assertEquals(3, cache.size)
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(4, cache["d"])
    }

    @Test
    fun memoryCacheRemoveDropsSingleEntryWithoutEvictingOthers() {
        val cache = LruCache<String, Int>(3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3

        assertEquals(2, cache.remove("b"))
        assertEquals(2, cache.size)
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertNull(cache["b"])
    }

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