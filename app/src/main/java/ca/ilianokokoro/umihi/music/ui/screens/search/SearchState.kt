package ca.ilianokokoro.umihi.music.ui.screens.search

import ca.ilianokokoro.umihi.music.models.Song


data class SearchState(
    val search: String = "",
    val screenState: ScreenState = ScreenState.Idle,
    /** Songs to show when the search box is empty — based on local listening history. */
    val recommendations: List<Song> = emptyList(),
    val recommendationsLoading: Boolean = true,
)


sealed class ScreenState {
    data class Success(
        val results: List<Song> = listOf()
    ) : ScreenState()

    data object Loading : ScreenState()
    data object Idle : ScreenState()
    data object Typing : ScreenState()
    data class Error(val exception: Exception) : ScreenState()
}
