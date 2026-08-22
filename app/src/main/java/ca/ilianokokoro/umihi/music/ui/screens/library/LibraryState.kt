package ca.ilianokokoro.umihi.music.ui.screens.library

import ca.ilianokokoro.umihi.music.models.PlaylistInfo

data class LibraryState(
    val screenState: LibraryScreenState = LibraryScreenState.Loading,
    val isRefreshing: Boolean = false,
    /** Count of fully-downloaded songs, kept live via a Room Flow. */
    val downloadedSongCount: Int = 0,
)

sealed class LibraryScreenState {
    data class LoggedIn(
        val playlistInfos: List<PlaylistInfo>
    ) : LibraryScreenState()

    data object LoggedOut : LibraryScreenState()
    data object Loading : LibraryScreenState()
    data class Error(val exception: Exception) : LibraryScreenState()
}
