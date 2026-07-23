package ca.ilianokokoro.umihi.music.data.repositories

import android.app.Application
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.datasources.PlaylistDataSource
import ca.ilianokokoro.umihi.music.extensions.toException
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.PlaylistSongCrossRef
import ca.ilianokokoro.umihi.music.models.Privacy
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class PlaylistRepository(application: Application) {
    private val playlistDataSource = PlaylistDataSource()
    private val localPlaylistDataSource = AppDatabase.getInstance(application).playlistRepository()
    private val localSongDataSource = AppDatabase.getInstance(application).songRepository()

    fun retrieveAll(settings: UmihiSettings): Flow<ApiResult<List<PlaylistInfo>>> {
        return flow {
            emit(ApiResult.Loading)
            try {
                val remotePlaylists = playlistDataSource.retrieveAll(settings)
                emit(ApiResult.Success(remotePlaylists))
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                val localPlaylists = localPlaylistDataSource.getAll().map { it.info }
                emit(ApiResult.Success(localPlaylists))
            }
        }.flowOn(Dispatchers.IO)
    }

    fun retrieveOne(
        playlist: Playlist,
        settings: UmihiSettings
    ): Flow<ApiResult<Playlist>> {
        return flow {
            emit(ApiResult.Loading)

            if (playlist.info.id == Constants.Downloads.DOWNLOADED_PLAYLIST_ID) {
                val downloadedSongs = localSongDataSource.getDownloadedSongs()
                emit(ApiResult.Success(Playlist(info = playlist.info, songs = downloadedSongs)))
                return@flow
            }

            try {
                val remotePlaylist = playlistDataSource.retrieveOne(playlist, settings)
                // Persist playlist info + song cross-refs so PlaylistMembership.memberIds
                // is accurate for songs fetched from YT Music.  insertSongs uses IGNORE so
                // already-downloaded local records (with audioFilePath / thumbnailPath) are
                // never overwritten by path-less remote copies.
                runCatching { localPlaylistDataSource.insertPlaylistWithSongs(remotePlaylist) }
                val localPlaylist = localPlaylistDataSource.getPlaylistById(playlist.info.id)
                emit(ApiResult.Success(mergeWithLocal(remotePlaylist, localPlaylist)))
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                }
                val localPlaylist = localPlaylistDataSource.getPlaylistById(playlist.info.id)
                if (localPlaylist != null) {
                    emit(
                        ApiResult.Success(
                            localPlaylist.copy(songs = localPlaylist.songs.filter { it.downloaded })
                        )
                    )
                } else {
                    emit(ApiResult.Error(e.toException()))
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    fun create(
        title: String,
        description: String,
        privacy: Privacy,
        settings: UmihiSettings
    ): Flow<ApiResult<PlaylistInfo?>> {
        return flow {
            emit(ApiResult.Loading)
            emit(
                ApiResult.Success(
                    playlistDataSource.create(title, description, privacy, settings)
                )
            )
        }.flowOn(Dispatchers.IO)
    }

    fun delete(
        playlist: PlaylistInfo,
        settings: UmihiSettings
    ): Flow<ApiResult<Unit>> {
        return flow {
            emit(ApiResult.Loading)
            emit(ApiResult.Success(playlistDataSource.delete(playlist, settings)))
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Add [videoId] to a YouTube Music playlist and immediately update the local cross-ref
     * table so [PlaylistMembership] reflects the change without waiting for a remote refresh.
     */
    fun addSong(
        playlistId: String,
        videoId: String,
        settings: UmihiSettings
    ): Flow<ApiResult<Unit>> {
        return flow {
            emit(ApiResult.Loading)
            playlistDataSource.addSong(playlistId, videoId, settings)
            // Optimistically write the cross-ref so the membership Flow updates immediately.
            runCatching {
                localPlaylistDataSource.insertCrossRefs(
                    listOf(PlaylistSongCrossRef(playlistId, videoId))
                )
            }
            emit(ApiResult.Success(Unit))
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Remove [videoId] from a YouTube Music playlist and immediately delete the local cross-ref
     * so [PlaylistMembership] reflects the removal without waiting for a remote refresh.
     *
     * @param setVideoId  Per-entry token (`playlistSetVideoId`) required by YT Music's
     *                    ACTION_REMOVE_VIDEO to reliably identify the exact entry to delete.
     *                    Pass the value from [Song.setVideoId]; null falls back to video-ID only.
     */
    fun removeSong(
        playlistId: String,
        videoId: String,
        setVideoId: String? = null,
        settings: UmihiSettings
    ): Flow<ApiResult<Unit>> {
        return flow {
            emit(ApiResult.Loading)
            playlistDataSource.removeSong(playlistId, videoId, setVideoId, settings)
            // Optimistically delete the cross-ref so the membership Flow updates immediately.
            runCatching {
                localPlaylistDataSource.deleteCrossRef(playlistId, videoId)
            }
            emit(ApiResult.Success(Unit))
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Move [videoId] from [fromPlaylistId] to [toPlaylistId] on YouTube Music and locally.
     * Both the remove and add operations are performed sequentially.
     */
    fun moveSong(
        fromPlaylistId: String,
        toPlaylistId: String,
        videoId: String,
        settings: UmihiSettings
    ): Flow<ApiResult<Unit>> {
        return flow {
            emit(ApiResult.Loading)
            // Remove from old playlist
            playlistDataSource.removeSong(fromPlaylistId, videoId, null, settings)
            runCatching { localPlaylistDataSource.deleteCrossRef(fromPlaylistId, videoId) }
            // Add to new playlist
            playlistDataSource.addSong(toPlaylistId, videoId, settings)
            runCatching {
                localPlaylistDataSource.insertCrossRefs(
                    listOf(PlaylistSongCrossRef(toPlaylistId, videoId))
                )
            }
            emit(ApiResult.Success(Unit))
        }.flowOn(Dispatchers.IO)
    }

    /**
     * Reactive stream of all song IDs that currently belong to at least one local playlist.
     * Backed by Room, so it emits whenever any cross-ref is inserted or deleted.
     */
    fun observeMembershipIds(): Flow<Set<String>> =
        localPlaylistDataSource.observeAllCrossRefs()
            .map { refs -> refs.map { it.songId }.toSet() }

    private fun mergeWithLocal(remotePlaylist: Playlist, localPlaylist: Playlist?): Playlist {
        if (localPlaylist == null) {
            return remotePlaylist
        }
        val localMap = localPlaylist.songs.associateBy { it.youtubeId }
        val mergedSongs = remotePlaylist.songs.map { remoteSong ->
            // Keep the remote song's metadata (title, artist, uid, etc.) but overlay the
            // download-related paths from the local record so that already-downloaded songs
            // correctly show as downloaded.  Do NOT replace the uid — stable uids allow
            // LazyColumn to animate items correctly and avoid full-list recompositions.
            val localSong = localMap[remoteSong.youtubeId]
            if (localSong != null && localSong.downloaded) {
                remoteSong.copy(
                    audioFilePath = localSong.audioFilePath,
                    thumbnailPath = localSong.thumbnailPath,
                    streamUrl = localSong.streamUrl ?: remoteSong.streamUrl
                )
            } else {
                remoteSong
            }
        }
        return remotePlaylist.copy(songs = mergedSongs)
    }
}
