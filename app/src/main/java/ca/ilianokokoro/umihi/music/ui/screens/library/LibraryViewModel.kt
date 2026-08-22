package ca.ilianokokoro.umihi.music.ui.screens.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.PlaylistRepository
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Privacy
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LibraryState())
    val uiState = _uiState.asStateFlow()

    private val playlistRepository = PlaylistRepository(application)
    private val datastoreRepository = DatastoreRepository(application)
    private val localSongDataSource = AppDatabase.getInstance(application).songRepository()

    init {
        getPlaylists()
        observeDownloadedSongCount()
    }

    private fun observeDownloadedSongCount() {
        viewModelScope.launch {
            localSongDataSource.observeDownloadedSongs().collect { songs ->
                _uiState.update { it.copy(downloadedSongCount = songs.size) }
            }
        }
    }

    fun getPlaylists() {
        viewModelScope.launch {
            getPlaylistsSuspend()
        }
    }

    fun refreshPlaylists() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                refreshPlaylistsOnce()
            } catch (ex: Exception) {
                printe(message = ex.toString(), exception = ex)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun refreshPlaylistsOnce() {
        val settings = datastoreRepository.getSettings()
        if (settings.cookies.isEmpty()) {
            _uiState.update { it.copy(screenState = LibraryScreenState.LoggedOut) }
            return
        }
        val apiResult = playlistRepository.retrieveAll(settings)
            .first { result -> result is ApiResult.Success || result is ApiResult.Error }
        val playlists = when (apiResult) {
            is ApiResult.Success -> apiResult.data.toMutableList()
            is ApiResult.Error -> emptyList()
            ApiResult.Loading -> return
        }
        applyPlaylistFiltersAndUpdateState(playlists, settings)
    }

    suspend fun getPlaylistsSuspend() {
        try {
            val settings = datastoreRepository.getSettings()
            if (settings.cookies.isEmpty()) {
                _uiState.update { it.copy(screenState = LibraryScreenState.LoggedOut) }
                return
            }
            playlistRepository.retrieveAll(settings).collect { apiResult ->
                when (apiResult) {
                    ApiResult.Loading -> {
                        _uiState.update { it.copy(screenState = LibraryScreenState.Loading) }
                    }
                    is ApiResult.Success -> {
                        val playlists = apiResult.data.toMutableList()
                        applyPlaylistFiltersAndUpdateState(playlists, settings)
                    }
                    is ApiResult.Error -> {
                        printe(message = "Failed to load playlists", exception = apiResult.exception)
                        _uiState.update { it.copy(screenState = LibraryScreenState.Error(apiResult.exception)) }
                    }
                }
            }
        } catch (ex: Exception) {
            printe(message = ex.toString(), exception = ex)
        }
    }

    private fun applyPlaylistFiltersAndUpdateState(
        playlists: List<PlaylistInfo>,
        settings: UmihiSettings
    ) {
        val mutablePlaylists = playlists.toMutableList()
        // Always remove the podcast/"Episodes for Later" playlist — this is
        // a music-only app and podcast episodes have no place in the Library.
        mutablePlaylists.removeIf { it.id == Constants.YoutubeApi.PODCAST_PLAYLIST_ID }
        mutablePlaylists.removeIf { it.id == Constants.Downloads.DOWNLOADED_PLAYLIST_ID }
        _uiState.update { it.copy(screenState = LibraryScreenState.LoggedIn(mutablePlaylists)) }
    }

    fun createPlaylist(title: String, description: String, privacy: Privacy) {
        viewModelScope.launch {
            try {
                val settings = datastoreRepository.getSettings()
                if (settings.cookies.isEmpty()) {
                    _uiState.update { it.copy(screenState = LibraryScreenState.LoggedOut) }
                    return@launch
                }
                playlistRepository.create(title, description, privacy, settings)
                    .collect { apiResult ->
                        if (apiResult !is ApiResult.Success || apiResult.data == null) return@collect
                        val currentState = _uiState.value.screenState
                        if (currentState !is LibraryScreenState.LoggedIn) return@collect
                        val updatedPlaylists = currentState.playlistInfos.toMutableList()
                            .apply { add(index = 2.coerceAtMost(size), element = apiResult.data) }
                        _uiState.update {
                            it.copy(screenState = LibraryScreenState.LoggedIn(updatedPlaylists))
                        }
                    }
            } catch (ex: Exception) {
                printe(message = ex.toString(), exception = ex)
            }
        }
    }

    fun removePlaylistsFromList(playlistIds: Set<String>) {
        _uiState.update { currentState ->
            val loggedIn = currentState.screenState as? LibraryScreenState.LoggedIn
                ?: return@update currentState
            currentState.copy(
                screenState = loggedIn.copy(
                    playlistInfos = loggedIn.playlistInfos.filterNot { it.id in playlistIds }
                )
            )
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { LibraryViewModel(application) }
        }
    }
}
