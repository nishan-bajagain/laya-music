package ca.ilianokokoro.umihi.music.ui.screens.settings

import ca.ilianokokoro.umihi.music.models.UmihiSettings

data class SettingsState(
    val screenState: ScreenState = ScreenState.Loading,
    val showDownloadDeleteConfirm: Boolean = false,
    val showStorageDialog: Boolean = false,
    /** label → absolute path pairs returned by UmihiHelper.getAvailableStorageOptions() */
    val storageOptions: List<Pair<String, String>> = emptyList()
)

sealed class ScreenState {
    data class Success(
        val settings: UmihiSettings,
    ) : ScreenState()

    data object Loading : ScreenState()
    data class Error(val exception: Exception) : ScreenState()
}
