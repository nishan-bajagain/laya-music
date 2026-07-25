package ca.ilianokokoro.umihi.music.data.datasources.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.PlaylistSongCrossRef
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalPlaylistDataSource {
    @Transaction
    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<Playlist>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: String): Playlist?

    @Query(
        """
    SELECT DISTINCT songId
    FROM PlaylistSongCrossRef
    WHERE songId IN (:songIds)
"""
    )
    suspend fun getSongIdsWithPlaylist(songIds: List<String>): List<String>

    /**
     * Observe every row in the cross-ref table reactively.
     * Used by [PlaylistMembership] to maintain a live set of song IDs that belong to any playlist.
     */
    @Query("SELECT * FROM PlaylistSongCrossRef")
    fun observeAllCrossRefs(): Flow<List<PlaylistSongCrossRef>>

    /**
     * Delete a single song↔playlist association (e.g. after a remove-from-playlist action).
     */
    @Query("DELETE FROM PlaylistSongCrossRef WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deleteCrossRef(playlistId: String, songId: String)

    /**
     * Return all [PlaylistInfo] entries that contain [songId] according to the local cross-ref table.
     */
    @Query(
        """
        SELECT playlists.* FROM playlists
        INNER JOIN PlaylistSongCrossRef ON playlists.id = PlaylistSongCrossRef.playlistId
        WHERE PlaylistSongCrossRef.songId = :songId
    """
    )
    suspend fun getPlaylistInfosForSong(songId: String): List<PlaylistInfo>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun observePlaylistById(playlistId: String): Flow<Playlist?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlistInfo: PlaylistInfo)

    /**
     * Insert songs that do not already exist in the database.
     * Uses IGNORE so that songs already saved with download paths (audioFilePath / thumbnailPath)
     * are never overwritten by remote-only copies that have no paths.  Download path updates are
     * always applied explicitly via [LocalSongDataSource.create] (REPLACE) inside the workers.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(songs: List<Song>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRefs(refs: List<PlaylistSongCrossRef>)

    @Transaction
    suspend fun insertPlaylistWithSongs(
        playlist: Playlist,
    ) {
        insertPlaylist(playlist.info)
        val songs = playlist.songs
        insertSongs(songs)
        val refs = songs.map { song -> PlaylistSongCrossRef(playlist.info.id, song.youtubeId) }
        insertCrossRefs(refs)
    }

    @Query("DELETE FROM playlists")
    suspend fun deleteAll()

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: String)

    @Query("DELETE FROM PlaylistSongCrossRef WHERE playlistId = :playlistId")
    suspend fun deleteCrossRefsByPlaylistId(playlistId: String)

    @Transaction
    suspend fun deleteFullPlaylist(playlistId: String) {
        deleteCrossRefsByPlaylistId(playlistId)
        deletePlaylistById(playlistId)
    }
}