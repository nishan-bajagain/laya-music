package ca.ilianokokoro.umihi.music.ui.screens.auth

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.AccountInfoHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper.isNullOrInvalidId
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.models.Cookies
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsState())

    private val _eventsChannel = MutableSharedFlow<ScreenEvent.Out>()
    val eventFlow = _eventsChannel.asSharedFlow()
    private val datastoreRepository = DatastoreRepository(application)

    fun onPageFinished(url: String?) {
        viewModelScope.launch {
            if (url?.contains(Constants.Auth.END_URL) == true && !_uiState.value.isLoggedIn) {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                val sessionCookies = Cookies(cookies)
                // Persist the session before publishing LoginCompleted. This
                // prevents the next screen from observing a logged-in event
                // while the cookie store is still being written.
                saveCookies(sessionCookies)
                _uiState.update { it.copy(isLoggedIn = true) }
                _eventsChannel.emit(ScreenEvent.Out.LoginCompleted)
                // Fetch account profile info in background — non-fatal if it fails
                fetchAccountInfo(sessionCookies)
            }
        }
    }

    fun onDataSyncIdFound(result: String) {
        viewModelScope.launch {
            result
                .trim('"')
                .substringBefore("||")
                .takeUnless { it.isNullOrInvalidId() }
                ?.let { datastoreRepository.saveDataSyncId(it) }
        }
    }

    private suspend fun saveCookies(cookies: Cookies) {
        printd("Got cookies: $cookies")
        datastoreRepository.saveCookies(cookies)
    }

    private fun fetchAccountInfo(cookies: Cookies) {
        viewModelScope.launch {
            try {
                val settings = UmihiSettings(cookies = cookies)
                val responseJson = YoutubeApiClient.getAccountMenu(settings)
                val (name, email, avatarUrl) = AccountInfoHelper.parseAccountInfo(responseJson)
                if (name.isNotBlank() || email.isNotBlank() || avatarUrl.isNotBlank()) {
                    datastoreRepository.saveAccountInfo(name, email, avatarUrl)
                    printd("Account info saved: name=$name email=$email avatarUrl=${avatarUrl.take(80)}...")
                }
            } catch (e: Exception) {
                printe("Failed to fetch account info: ${e.message}", exception = e)
            }
        }
    }



    sealed interface ScreenEvent {
        sealed class Out {
            data object LoginCompleted : Out()
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AuthViewModel(application)
            }
        }
    }
}
