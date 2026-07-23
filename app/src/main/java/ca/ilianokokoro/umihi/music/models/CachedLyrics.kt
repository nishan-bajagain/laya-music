package ca.ilianokokoro.umihi.music.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import ca.ilianokokoro.umihi.music.core.Constants

/**
 * Room entity for caching lyrics locally.
 * Key is composed of videoId to ensure fast lookup.
 */
@Entity(tableName = Constants.Database.LYRICS_TABLE)
data class CachedLyrics(
    @PrimaryKey
    val videoId: String,
    /** Raw LRC-format synced lyrics, or null if not available. */
    val syncedLyrics: String?,
    /** Plain text lyrics, or null if not available. */
    val plainLyrics: String?,
    /** Source provider that returned these lyrics. */
    val provider: String,
    /** Epoch millis when this entry was cached. */
    val cachedAtMs: Long = System.currentTimeMillis()
)
