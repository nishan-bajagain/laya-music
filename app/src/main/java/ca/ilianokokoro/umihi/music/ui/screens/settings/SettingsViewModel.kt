package ca.ilianokokoro.umihi.music.ui.screens.settings

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.ExoCache
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.managers.ScreenAwakeManager
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.DownloadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsState())
    val uiState = _uiState.asStateFlow()

    private val _application = application
    private val datastoreRepository = DatastoreRepository(application)
    private val downloadRepository = DownloadRepository(application)

    fun logOut() {
        viewModelScope.launch {
            datastoreRepository.logOut()
            getSettings()
        }
    }

    fun getSettings() {
        viewModelScope.launch {
            val settings = datastoreRepository.getSettings()
            val storageOptions = UmihiHelper.getAvailableStorageOptions(_application)
            _uiState.update {
                _uiState.value.copy(
                    screenState = ScreenState.Success(settings = settings),
                    storageOptions = storageOptions
                )
            }
        }
    }

    fun clearLogins() {
        viewModelScope.launch {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            logOut()
            Toast.makeText(
                _application,
                _application.getString(R.string.login_info_cleared),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun updateShowDownloadDeleteConfirm(value: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                _uiState.value.copy(showDownloadDeleteConfirm = value)
            }
        }
    }

    fun showStorageDialog() {
        viewModelScope.launch {
            val storageOptions = UmihiHelper.getAvailableStorageOptions(_application)
            _uiState.update {
                _uiState.value.copy(
                    showStorageDialog = true,
                    storageOptions = storageOptions
                )
            }
        }
    }

    fun hideStorageDialog() {
        _uiState.update { it.copy(showStorageDialog = false) }
    }

    fun setDownloadPath(path: String?) {
        viewModelScope.launch {
            datastoreRepository.saveDownloadPath(path)
            getSettings()
        }
    }

    @OptIn(UnstableApi::class)
    fun clearDownloads() {
        viewModelScope.launch {
            downloadRepository.cancelAllWorks()
            AppDatabase.clearDownloads(_application)
            ExoCache(_application).clear()
            UmihiHelper.getDownloadDirectory(context = _application)
                .deleteRecursively()
            Toast.makeText(
                _application,
                _application.getString(R.string.downloads_cleared),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun updateAudioOffloadSetting(value: Boolean) {
        PlayerManager.setAudioOffloadEnabled(value)
        updateSetting(
            DatastoreRepository.PreferenceKeys.USE_AUDIO_OFFLOAD,
            value
        )
    }

    fun updateKeepScreenOnSetting(value: Boolean) {
        ScreenAwakeManager.setKeepScreenOn(value)
        updateSetting(
            DatastoreRepository.PreferenceKeys.KEEP_SCREEN_ON,
            value
        )
    }

    fun isLoggedIn(): Boolean {
        val state = _uiState.value.screenState
        if (state !is ScreenState.Success) {
            return false
        }
        return !state.settings.cookies.isEmpty()
    }

    fun <T> updateSetting(key: Preferences.Key<T>, value: T) {
        viewModelScope.launch {
            datastoreRepository.save(key, value)
            getSettings()
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(application)
            }
        }
    }
}
