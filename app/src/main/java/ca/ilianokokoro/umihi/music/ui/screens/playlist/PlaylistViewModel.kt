package ca.ilianokokoro.umihi.music.ui.screens.playlist


import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.DownloadRepository
import ca.ilianokokoro.umihi.music.data.repositories.PlaylistRepository
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.ui.navigation.viewmodels.SharedViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PlaylistViewModel(
    private val playlistInfo: PlaylistInfo,
    private val sharedViewModel: SharedViewModel,
    application: Application
) :
    AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        PlaylistState(
            screenState = ScreenState.Loading(playlistInfo)
        )
    )
    val uiState = _uiState.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun showSearch() {
        _uiState.update { it.copy(showingSearch = true) }
    }

    fun hideSearch() {
        _uiState.update { it.copy(showingSearch = false, searchQuery = "") }
    }

    private val playlistRepository = PlaylistRepository(application)
    private val localPlaylistRepository = AppDatabase.getInstance(application).playlistRepository()
    private val localSongDataSource = AppDatabase.getInstance(application).songRepository()
    private val datastoreRepository = DatastoreRepository(application)
    private val downloadRepository = DownloadRepository(application)

    init {
        observeSongDownloads()
        viewModelScope.launch {
            getPlaylistInfoAsync()
            observerDownloadJob()
        }
    }

    private fun observeSongDownloads() {
        viewModelScope.launch {
            if (playlistInfo.isDownloadedPlaylist) {
                // The downloaded playlist is not stored in the playlists Room table — it is a
                // virtual playlist backed by the songs table.  Observe the songs table directly
                // so the screen updates whenever a download finishes or a song is deleted.
                localSongDataSource.observeDownloadedSongs().collect { songs ->
                    _uiState.update { currentState ->
                        val screenState = currentState.screenState
                        if (screenState is ScreenState.Success) {
                            currentState.copy(
                                screenState = screenState.copy(
                                    playlist = screenState.playlist.copy(songs = songs)
                                )
                            )
                        } else {
                            currentState
                        }
                    }
                }
            } else {
                // Regular playlist — observe the Room cross-ref / download state as before.
                localPlaylistRepository.observePlaylistById(playlistInfo.id)
                    .collect { localPlaylist ->
                        if (localPlaylist != null) {
                            _uiState.update { currentState ->
                                val screenState = currentState.screenState
                                if (screenState is ScreenState.Success) {
                                    currentState.copy(
                                        screenState = screenState.copy(
                                            playlist = updatePlaylistFrom(
                                                screenState.playlist,
                                                localPlaylist
                                            )
                                        )
                                    )
                                } else {
                                    currentState
                                }
                            }
                        }
                    }
            }
        }
    }

    suspend fun observerDownloadJob() {
        val playlist = getPlaylist() ?: return
        val existingJobFlow = downloadRepository.getExistingJobFlow(playlist)

        existingJobFlow.collect { workInfos ->
            val workInfo = workInfos.firstOrNull() ?: return@collect

            _uiState.update {
                it.copy(
                    isDownloading =
                        workInfo.state == WorkInfo.State.ENQUEUED ||
                                workInfo.state == WorkInfo.State.RUNNING ||
                                workInfo.state == WorkInfo.State.BLOCKED
                )
            }

            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    printd("Download finished for ${playlist.info.title}")
                }

                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED -> {
                    printd("Download failed or cancelled for ${playlist.info.title}")
                }

                else -> {}
            }
        }
    }

    fun refreshPlaylistInfo() {
        viewModelScope.launch {
            _uiState.update {
                _uiState.value.copy(
                    isRefreshing = true
                )
            }
            getPlaylistInfoAsync()
            _uiState.update {
                _uiState.value.copy(
                    isRefreshing = false
                )
            }
        }

    }

    fun getPlaylistInfo() {
        viewModelScope.launch {
            getPlaylistInfoAsync()
        }
    }

    fun playPlaylist(startingSong: Song? = null) {
        val playlist = getPlaylist() ?: return
        viewModelScope.launch {
            PlayerManager.playPlaylist(
                playlist,
                startingSong?.let { playlist.songs.indexOf(it) } ?: 0
            )
        }
    }

    fun shufflePlaylist() {
        val playlist = getPlaylist() ?: return
        viewModelScope.launch {
            PlayerManager.shufflePlaylist(playlist)
        }
    }

    fun downloadPlaylist() {
        val playlist = getPlaylist() ?: return
        viewModelScope.launch {
            if (playlist.downloaded) {
                return@launch
            }

            val settings = datastoreRepository.getSettings()
            downloadRepository.downloadPlaylist(playlist, settings.downloadOnMetered)
        }
    }

    fun deletePlaylist(onBack: () -> Unit) {
        viewModelScope.launch {
            try {
                val settings = datastoreRepository.getSettings()
                if (settings.cookies.isEmpty()) {
                    throw Exception("Failed to get to login cookies")
                }

                playlistRepository.delete(playlistInfo, settings)
                    .collect { apiResult ->
                        _uiState.update { _ ->
                            _uiState.value.copy(
                                screenState = when (apiResult) {
                                    is ApiResult.Error -> {
                                        ScreenState.Error(Exception("Failed to delete the playlist"))
                                    }

                                    ApiResult.Loading -> ScreenState.Loading(playlistInfo)
                                    is ApiResult.Success -> {
                                        onBack()
                                        sharedViewModel.markPlaylistDeleted(
                                            playlistInfo
                                        )
                                        ScreenState.Success(Playlist(PlaylistInfo()))
                                    }
                                }
                            )
                        }
                    }

            } catch (ex: Exception) {
                printe(message = ex.toString(), exception = ex)
                _uiState.update {
                    _uiState.value.copy(
                        screenState = ScreenState.Error(ex)
                    )
                }
            }
        }
    }

    fun deleteLocalPlaylist() {
        val playlist = getPlaylist() ?: return
        viewModelScope.launch {
            downloadRepository.deletePlaylist(playlist)
            getPlaylistInfoAsync()
        }
    }

    fun cancelDownload() {
        if (!uiState.value.isDownloading) {
            return
        }
        val playlist = getPlaylist() ?: return
        viewModelScope.launch {
            downloadRepository.cancelPlaylistDownload(playlist)
        }
    }


    fun downloadSong(song: Song) {
        val playlist = getPlaylist() ?: return
        if (song.downloaded) {
            return
        }
        viewModelScope.launch {
            val settings = datastoreRepository.getSettings()
            downloadRepository.downloadSong(playlist, song, settings.downloadOnMetered)
        }
    }

    /**
     * Delete the downloaded files for a single song without removing it from any playlist.
     * The Room observer in [observeSongDownloads] picks up the path change automatically
     * and removes the song from the Downloaded virtual playlist view.
     */
    fun deleteDownloadedSong(song: Song) {
        viewModelScope.launch {
            try {
                downloadRepository.deleteSingleDownload(song)
            } catch (ex: Exception) {
                printe("Failed to delete downloaded song ${song.youtubeId}: ${ex.message}", exception = ex)
            }
        }
    }

    fun removeSongFromPlaylist(song: Song) {
        val playlist = getPlaylist() ?: return
        viewModelScope.launch {
            try {
                val settings = datastoreRepository.getSettings()
                if (settings.cookies.isEmpty()) return@launch
                playlistRepository.removeSong(
                    playlistId = playlist.info.id,
                    videoId = song.youtubeId,
                    setVideoId = song.setVideoId,
                    settings = settings
                ).collect { /* ignore result — refresh will pick up the change */ }
                // Refresh the playlist so the removed song disappears
                getPlaylistInfoAsync()
            } catch (ex: Exception) {
                ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe(
                    message = "Failed to remove song from playlist: ${ex.message}",
                    exception = ex
                )
            }
        }
    }

    private suspend fun getPlaylistInfoAsync() {
        try {
            val settings = datastoreRepository.getSettings()

            playlistRepository.retrieveOne(Playlist(playlistInfo), settings)
                .collect { apiResult ->
                    _uiState.update { _ ->
                        _uiState.value.copy(
                            screenState = when (apiResult) {
                                is ApiResult.Error -> {
                                    ScreenState.Error(apiResult.exception)
                                }

                                ApiResult.Loading -> ScreenState.Loading(playlistInfo)
                                is ApiResult.Success -> {
                                    ScreenState.Success(playlist = apiResult.data)
                                }
                            }
                        )
                    }
                }

        } catch (ex: Exception) {
            printe(message = ex.toString(), exception = ex)
            _uiState.update {
                _uiState.value.copy(
                    screenState = ScreenState.Error(ex)
                )
            }
        }

    }

    private fun updatePlaylistFrom(oldPlaylist: Playlist, updatedPlaylist: Playlist?): Playlist {
        if (updatedPlaylist == null) {
            return oldPlaylist
        }
        val localMap = updatedPlaylist.songs.associateBy { it.youtubeId }
        val mergedSongs = oldPlaylist.songs.map { remoteSong ->
            // Overlay only the download-related fields from the local record so that songs
            // which just finished downloading immediately reflect their new state.
            // Keep the remote song's uid so LazyColumn keys stay stable and no unnecessary
            // animations or full-list recompositions are triggered.
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
        return oldPlaylist.copy(songs = mergedSongs)
    }

    private fun getPlaylist(): Playlist? {
        val screenState = _uiState.value.screenState
        if (screenState !is ScreenState.Success) {
            return null
        }
        return screenState.playlist
    }

    companion object {
        fun Factory(
            playlistInfo: PlaylistInfo,
            sharedViewModel: SharedViewModel,
            application: Application
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PlaylistViewModel(playlistInfo, sharedViewModel, application)
                }
            }
    }
}