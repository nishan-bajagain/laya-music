package ca.ilianokokoro.umihi.music.ui.screens.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeDataExtractor
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.SongRepository
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SearchState())
    val uiState = _uiState.asStateFlow()

    val songRepository = SongRepository()
    private val localSongRepository = AppDatabase.getInstance(application).songRepository()
    private val localPlaylistRepository = AppDatabase.getInstance(application).playlistRepository()

    /** Tracks the active search coroutine so it can be cancelled before a new one starts. */
    private var activeSearchJob: Job? = null

    init {
        loadRecommendations()
    }

    /**
     * Build "Recommended for You" list:
     *  1. YouTube Music home feed (personalized when signed in, popular otherwise)
     *  2. Fallback: local downloaded songs — shuffled so order varies
     *
     * Runs on IO — never blocks playback or scrolling.
     */
    private fun loadRecommendations() {
        viewModelScope.launch(Dispatchers.IO) {
            // ── 1. Try YouTube Music home feed ────────────────────────────────
            try {
                val settings = DatastoreRepository(getApplication()).getSettings()
                val homeJson = YoutubeApiClient.browse(
                    browseId = Constants.YoutubeApi.Browse.HOME_BROWSE_ID,
                    settings = settings
                )
                val ytmSongs = YoutubeDataExtractor.extractHomeRecommendations(homeJson)
                if (ytmSongs.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            recommendations = ytmSongs.take(30),
                            recommendationsLoading = false
                        )
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // fall through to local fallback
            }

            // ── 2. Fallback: local downloaded songs ───────────────────────────
            try {
                val downloaded = localSongRepository.getDownloadedSongs()
                _uiState.update {
                    it.copy(
                        recommendations = downloaded.shuffled().take(30),
                        recommendationsLoading = false
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(recommendationsLoading = false) }
            }
        }
    }

    fun search() {
        // Cancel any in-flight search: a slow older request must not overwrite a newer result.
        activeSearchJob?.cancel()
        activeSearchJob = viewModelScope.launch {
            val query = uiState.value.search
            if (query.isBlank()) {
                _uiState.update {
                    _uiState.value.copy(
                        screenState = ScreenState.Success(results = listOf())
                    )
                }
                return@launch
            }

            songRepository.search(query).collect { apiResult ->
                _uiState.update {
                    _uiState.value.copy(
                        screenState = when (apiResult) {
                            ApiResult.Loading -> ScreenState.Loading
                            is ApiResult.Error -> ScreenState.Error(apiResult.exception)
                            is ApiResult.Success -> ScreenState.Success(results = apiResult.data)
                        }
                    )
                }
            }
        }
    }

    fun onSearchFieldChange(newValue: String) {
        _uiState.update { it.copy(search = newValue) }
    }

    /** Reload recommendations (e.g. after a new download completes). */
    fun refreshRecommendations() {
        _uiState.update { it.copy(recommendationsLoading = true) }
        loadRecommendations()
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(application) }
        }
    }
}
