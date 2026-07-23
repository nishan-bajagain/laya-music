package ca.ilianokokoro.umihi.music.core.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.helpers.DownloadHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.LyricsRepository
import ca.ilianokokoro.umihi.music.data.repositories.SongRepository
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class SongDownloadWorker(
    private val appContext: Context,
    private val params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val playlistRepository = AppDatabase.getInstance(appContext).playlistRepository()
    private val localSongRepository = AppDatabase.getInstance(appContext).songRepository()
    private val songRepository = SongRepository()

    override suspend fun doWork(): Result {
        // playlistId is optional — null means standalone (not tied to any playlist)
        val playlistId = params.inputData.getString(PLAYLIST_KEY)

        val songId = params.inputData.getString(SONG_KEY)
            ?: return Result.failure()

        val song = localSongRepository.getSong(songId)
            ?: return Result.failure()

        return try {
            // Get custom download path from settings
            val customBasePath = DatastoreRepository(appContext).getSettings().downloadPath

            // Update playlist cover image only when this is a playlist-linked download
            if (playlistId != null) {
                val playlist = playlistRepository.getPlaylistById(playlistId)
                if (playlist != null) {
                    val playlistImage = DownloadHelper.downloadImage(
                        appContext,
                        playlist.info.coverHref,
                        playlist.info.id,
                        customBasePath
                    )
                    playlistRepository.insertPlaylist(
                        playlist.info.copy(coverPath = playlistImage?.path)
                    )
                }
            }

            val fullSongData = songRepository
                .getSongInfo(song.youtubeId)
                .first { it is ApiResult.Success }

            val fullSong = (fullSongData as ApiResult.Success).data

            val audioPath = DownloadHelper.downloadAudio(appContext, song, customBasePath = customBasePath)
                ?: throw IOException("Audio download returned null for ${song.youtubeId}")

            val thumbnailFile = DownloadHelper.downloadImage(
                appContext,
                fullSong.thumbnailHref,
                song.youtubeId,
                customBasePath
            )

            // If thumbnail download fails, the DB query (audioFilePath IS NOT NULL AND
            // thumbnailPath IS NOT NULL) would permanently hide the song in the Downloads list
            // while leaving an orphaned audio file on disk.  Fail here so WorkManager marks
            // the job as FAILED (rather than SUCCEEDED with invisible data), the notification
            // shows a failure, and the user can retry.  Clean up the audio file first.
            if (thumbnailFile == null) {
                runCatching { java.io.File(audioPath).delete() }
                throw IOException("Thumbnail download failed for ${song.youtubeId}; aborting to prevent orphaned audio file")
            }

            // Always write the authoritative metadata from the API response.
            // `song` (loaded from DB) may have been created as a skeleton
            // Song(youtubeId, streamUrl) by setStreamUrl() — its title/artist
            // could be blank. `fullSong` always has the complete metadata.
            val updatedSong = song.copy(
                title          = fullSong.title.takeIf { it.isNotBlank() } ?: song.title,
                artist         = fullSong.artist.takeIf { it.isNotBlank() } ?: song.artist,
                duration       = fullSong.duration.takeIf { it.isNotBlank() } ?: song.duration,
                thumbnailHref  = fullSong.thumbnailHref.takeIf { it.isNotBlank() } ?: song.thumbnailHref,
                isExplicit     = fullSong.isExplicit,
                thumbnailPath  = thumbnailFile.path,
                audioFilePath  = audioPath,
            )

            localSongRepository.create(updatedSong)

            // Proactively cache lyrics so they are available offline
            try {
                val lyricsRepo = LyricsRepository(appContext)
                val durationSec = parseDurationToSeconds(song.duration)
                lyricsRepo.getLyrics(
                    videoId = song.youtubeId,
                    title = song.title,
                    artist = song.artist,
                    durationSeconds = durationSec
                )
                printd("Lyrics cached for offline use: ${song.title}")
            } catch (e: Exception) {
                // Lyrics failure must never fail the download
                printe("Lyrics fetch skipped for ${song.youtubeId}: ${e.message}", exception = e)
            }

            NotificationManager.showSongDownloadSuccess(appContext, song)

            Result.success()
        } catch (_: CancellationException) {
            printd("Song download canceled ${song.title}")
            Result.failure()
        } catch (e: Exception) {
            NotificationManager.showSongDownloadFailed(appContext, song)

            printe(
                message = "Error downloading song: ${song.youtubeId}",
                exception = e
            )

            Result.failure()
        }
    }

    private fun parseDurationToSeconds(duration: String): Int? {
        return try {
            val parts = duration.trim().split(":").map { it.toInt() }
            when (parts.size) {
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PLAYLIST_KEY = "playlist"
        const val SONG_KEY = "song"
    }
}
