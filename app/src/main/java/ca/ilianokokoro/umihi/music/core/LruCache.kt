package ca.ilianokokoro.umihi.music.core

import java.util.LinkedHashMap

/**
 * A small thread-safe LRU cache with a fixed capacity.
 *
 * Entries are evicted in least-recently-used order once [maxEntries] is
 * exceeded: both `put` and a successful `get` refresh an entry's recency, so
 * hot entries survive while cold ones age out. All operations are synchronized
 * on the cache instance.
 */
internal class LruCache<K, V>(
    private val maxEntries: Int
) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @get:Synchronized
    val size: Int
        get() = map.size
}
