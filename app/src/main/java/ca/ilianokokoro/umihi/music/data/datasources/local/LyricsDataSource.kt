package ca.ilianokokoro.umihi.music.data.datasources.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.models.CachedLyrics

@Dao
interface LyricsDataSource {

    @Query("SELECT * FROM ${Constants.Database.LYRICS_TABLE} WHERE videoId = :videoId LIMIT 1")
    suspend fun getLyrics(videoId: String): CachedLyrics?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLyrics(lyrics: CachedLyrics)

    @Query("DELETE FROM ${Constants.Database.LYRICS_TABLE} WHERE videoId = :videoId")
    suspend fun deleteLyrics(videoId: String)

    @Query("DELETE FROM ${Constants.Database.LYRICS_TABLE} WHERE cachedAtMs < :expiryMs")
    suspend fun deleteExpired(expiryMs: Long)

    @Query("DELETE FROM ${Constants.Database.LYRICS_TABLE}")
    suspend fun deleteAll()
}
