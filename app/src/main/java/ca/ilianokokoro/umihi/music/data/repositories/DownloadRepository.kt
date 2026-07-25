package ca.ilianokokoro.umihi.music.data.repositories

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.ConnectivityHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.core.workers.PlaylistDownloadWorker
import ca.ilianokokoro.umihi.music.core.workers.SongDownloadWorker
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class DownloadRepository(appContext: Context) {
    private val _appContext = appContext
    private val workManager: WorkManager = WorkManager.getInstance(_appContext)
    private val localPlaylistRepository = AppDatabase.getInstance(_appContext).playlistRepository()
    private val localSongRepository = AppDatabase.getInstance(_appContext).songRepository()

    suspend fun downloadPlaylist(playlist: Playlist, useMetered: Boolean = false) {
        val existingWork = getExistingJobs(playlist.info.id)
        if (existingWork.isNotEmpty()) {
            printd("Download is already ongoing for playlist ${playlist.info.title}")
            return
        }
        localPlaylistRepository.insertPlaylistWithSongs(playlist)
        val request = OneTimeWorkRequestBuilder<PlaylistDownloadWorker>().setInputData(
            workDataOf(
                PlaylistDownloadWorker.PLAYLIST_KEY to playlist.info.id
            )
        ).setConstraints(
            Constraints(
                requiredNetworkType = if (useMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                requiresStorageNotLow = true
            )
        ).build()


        workManager.enqueueUniqueWork(playlist.info.id, ExistingWorkPolicy.KEEP, request)

        if (!useMetered && ConnectivityHelper.isMeteredNetwork(_appContext)) {
            NotificationManager.showPlaylistDownloadWaitingForWifi(_appContext, playlist)
        }
    }

    suspend fun deletePlaylist(playlist: Playlist) {
        localPlaylistRepository.deleteFullPlaylist(playlist.info.id)
        val audioDir =
            UmihiHelper.getDownloadDirectory(
                _appContext,
                Constants.Downloads.AUDIO_FILES_FOLDER
            )
        val imageDir =
            UmihiHelper.getDownloadDirectory(_appContext, Constants.Downloads.THUMBNAILS_FOLDER)

        File(
            imageDir,
            _appContext.getString(R.string.jpg_extension, playlist.info.id)
        ).takeIf { it.exists() }?.delete()

        val songIds = playlist.songs.map { it.youtubeId }
        val stillLinked = localPlaylistRepository.getSongIdsWithPlaylist(songIds).toSet()
        val songsToClear = mutableListOf<String>()
        playlist.songs.forEach { song ->
            if (song.youtubeId in stillLinked) {
                return@forEach
            }
            songsToClear.add(song.youtubeId)
            File(
                audioDir,
                _appContext.getString(R.string.webm_extension, song.youtubeId)
            ).takeIf { it.exists() }?.delete()
            File(
                imageDir,
                _appContext.getString(R.string.jpg_extension, song.youtubeId)
            ).takeIf { it.exists() }?.delete()
        }
        localSongRepository.deleteByIds(songsToClear)
    }

    suspend fun downloadSong(playlist: Playlist, song: Song, useMetered: Boolean = false) {
        val id = "${playlist.info.id}${song.youtubeId}"
        val existingWork = getExistingJobs(id)
        if (existingWork.isNotEmpty()) {
            printd("Download is already ongoing for song ${playlist.info.title}")
            return
        }

        localPlaylistRepository.insertPlaylistWithSongs(playlist)
        val request = OneTimeWorkRequestBuilder<SongDownloadWorker>().setInputData(
            workDataOf(
                SongDownloadWorker.PLAYLIST_KEY to playlist.info.id,
                SongDownloadWorker.SONG_KEY to song.youtubeId
            )
        ).setConstraints(
            Constraints(
                requiredNetworkType = if (useMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                requiresStorageNotLow = true
            )
        ).build()


        workManager.enqueueUniqueWork(
            id,
            ExistingWorkPolicy.KEEP,
            request
        )

        if (!useMetered && ConnectivityHelper.isMeteredNetwork(_appContext)) {
            NotificationManager.showSongDownloadWaitingForWifi(_appContext, song)
        }
    }

    /**
     * Download a single song without requiring it to belong to any playlist.
     * The song is saved to the local database before enqueueing so the worker can find it.
     */
    suspend fun downloadSongStandalone(song: Song, useMetered: Boolean = false) {
        val id = "standalone_${song.youtubeId}"
        val existingWork = getExistingJobs(id)
        if (existingWork.isNotEmpty()) {
            printd("Download already ongoing for standalone song ${song.title}")
            return
        }

        // Persist song so the worker can load it from the DB.
        // Use IGNORE so we never overwrite an already-downloaded song's audioFilePath /
        // thumbnailPath with a path-less remote copy.
        localSongRepository.createIfNotExists(song)

        val request = OneTimeWorkRequestBuilder<SongDownloadWorker>()
            .setInputData(
                workDataOf(SongDownloadWorker.SONG_KEY to song.youtubeId)
                // Note: no PLAYLIST_KEY → SongDownloadWorker treats this as standalone
            )
            .setConstraints(
                Constraints(
                    requiredNetworkType = if (useMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                    requiresStorageNotLow = true
                )
            )
            .build()

        workManager.enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request)

        if (!useMetered && ConnectivityHelper.isMeteredNetwork(_appContext)) {
            NotificationManager.showSongDownloadWaitingForWifi(_appContext, song)
        }
    }

    /**
     * Delete the audio and thumbnail files for a single downloaded song,
     * then clear its file paths in the database.
     * The song row itself is kept so playlist membership is not lost.
     */
    suspend fun deleteSingleDownload(song: Song) {
        withContext(Dispatchers.IO) {
            // Cancel any in-progress download worker for this song
            workManager.cancelUniqueWork("standalone_${song.youtubeId}")

            val audioDir = UmihiHelper.getDownloadDirectory(
                _appContext, Constants.Downloads.AUDIO_FILES_FOLDER
            )
            val imageDir = UmihiHelper.getDownloadDirectory(
                _appContext, Constants.Downloads.THUMBNAILS_FOLDER
            )

            File(audioDir, _appContext.getString(R.string.webm_extension, song.youtubeId))
                .takeIf { it.exists() }?.delete()
            File(imageDir, _appContext.getString(R.string.jpg_extension, song.youtubeId))
                .takeIf { it.exists() }?.delete()

            // Nullify paths so Song.downloaded becomes false — row stays for playlist links
            localSongRepository.clearDownloadPaths(song.youtubeId)
        }
    }

    fun cancelPlaylistDownload(playlist: Playlist) {
        printd("stopping work ${playlist.info.title}")
        workManager.cancelUniqueWork(playlist.info.id)
    }

    fun cancelAllWorks() {
        workManager.cancelAllWork()
    }

    fun getExistingJobFlow(playlist: Playlist): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosForUniqueWorkFlow(playlist.info.id)
    }

    /**
     * Returns a Flow of WorkInfo for a standalone song download so the UI can
     * observe download state in real time even after the user navigates away and back.
     */
    fun getStandaloneWorkInfoFlow(songId: String): Flow<List<WorkInfo>> {
        return workManager.getWorkInfosForUniqueWorkFlow("standalone_$songId")
    }

    private suspend fun getExistingJobs(id: String): List<WorkInfo> {
        return withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(id).get().filter {
                it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.BLOCKED
            }
        }
    }
}
