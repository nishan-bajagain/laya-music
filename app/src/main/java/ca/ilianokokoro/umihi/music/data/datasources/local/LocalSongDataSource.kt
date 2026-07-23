package ca.ilianokokoro.umihi.music.data.datasources.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSongDataSource {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun create(song: Song)

    @Query(
        """
    SELECT * 
    FROM songs 
    WHERE audioFilePath IS NOT NULL 
      AND thumbnailPath IS NOT NULL
      ORDER BY  
        songs.title COLLATE NOCASE ASC,
        songs.artist COLLATE NOCASE ASC
"""
    )
    suspend fun getDownloadedSongs(): List<Song>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createAll(songs: List<Song>)

    /**
     * Reactive version of [getDownloadedSongs].
     * Emits a new list every time a song is fully downloaded (audioFilePath + thumbnailPath both
     * set) or a downloaded song is removed.  Used by [HomeViewModel] to keep the card count live
     * and by [PlaylistViewModel] to keep the Downloaded playlist content live while it is open.
     */
    @Query(
        """
    SELECT * 
    FROM songs 
    WHERE audioFilePath IS NOT NULL 
      AND thumbnailPath IS NOT NULL
      ORDER BY  
        songs.title COLLATE NOCASE ASC,
        songs.artist COLLATE NOCASE ASC
"""
    )
    fun observeDownloadedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE youtubeId = :songId")
    suspend fun getSong(songId: String): Song?

    /**
     * Insert the song only if no row with the same [Song.youtubeId] exists yet.
     * Use this when you want to ensure an already-downloaded song is NOT overwritten
     * by a remote-only copy that has no [Song.audioFilePath] / [Song.thumbnailPath].
     * Workers that need to store the completed download paths must still use [create] (REPLACE).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun createIfNotExists(song: Song)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    suspend fun setStreamUrl(songId: String, streamUrl: String) {
        val existing = getSong(songId) ?: Song(
            youtubeId = songId,
            streamUrl = streamUrl
        )

        val updated = existing.copy(streamUrl = streamUrl)
        create(updated)
    }

    @Query("DELETE FROM songs WHERE youtubeId IN (:songIds)")
    suspend fun deleteByIds(songIds: List<String>)

    /**
     * Remove only the local file paths for a downloaded song.
     * The song row itself stays so playlist membership is preserved;
     * the [Song.downloaded] computed property will now return false.
     */
    @Query("UPDATE songs SET audioFilePath = NULL, thumbnailPath = NULL WHERE youtubeId = :youtubeId")
    suspend fun clearDownloadPaths(youtubeId: String)

    @Delete
    suspend fun delete(song: Song)

}