package ca.ilianokokoro.umihi.music.ui.screens.home

import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Song


data class HomeState(
    val screenState: ScreenState = ScreenState.Loading,
    val isRefreshing: Boolean = false,
    /** Count of fully-downloaded songs, kept live via a Room Flow. */
    val downloadedSongCount: Int = 0,
    /** Live recommendations for the compact home rail. */
    val recommendations: List<Song> = emptyList(),
    val recommendationsLoading: Boolean = true,
)

sealed class ScreenState {
    data class LoggedIn(
        val playlistInfos: List<PlaylistInfo>
    ) : ScreenState()

    data object LoggedOut
        : ScreenState()


    data object Loading : ScreenState()
    data class Error(val exception: Exception) : ScreenState()
}