package ca.ilianokokoro.umihi.music.ui.screens.auth

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.Constants
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SettingsState())

    private val _eventsChannel = MutableSharedFlow<ScreenEvent.Out>()
    val eventFlow = _eventsChannel.asSharedFlow()
    private val datastoreRepository = DatastoreRepository(application)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun onPageFinished(url: String?) {
        viewModelScope.launch {
            if (url?.contains(Constants.Auth.END_URL) == true && !_uiState.value.isLoggedIn) {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                saveCookies(Cookies(cookies))
                _uiState.update { it.copy(isLoggedIn = true) }
                _eventsChannel.emit(ScreenEvent.Out.LoginCompleted)
                // Fetch account profile info in background — non-fatal if it fails
                fetchAccountInfo(Cookies(cookies))
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

    private fun saveCookies(cookies: Cookies) {
        printd("Got cookies: $cookies")
        viewModelScope.launch {
            datastoreRepository.saveCookies(cookies)
        }
    }

    private fun fetchAccountInfo(cookies: Cookies) {
        viewModelScope.launch {
            try {
                val settings = UmihiSettings(cookies = cookies)
                val responseJson = YoutubeApiClient.getAccountMenu(settings)
                val (name, email, avatarUrl) = parseAccountInfo(responseJson)
                if (name.isNotBlank() || email.isNotBlank() || avatarUrl.isNotBlank()) {
                    datastoreRepository.saveAccountInfo(name, email, avatarUrl)
                    printd("Account info saved: name=$name email=$email")
                }
            } catch (e: Exception) {
                printe("Failed to fetch account info: ${e.message}", exception = e)
            }
        }
    }

    /**
     * Parses the account_menu API response.
     * Returns Triple(name, email, avatarUrl) — fields are blank on parse failure.
     */
    private fun parseAccountInfo(jsonString: String): Triple<String, String, String> {
        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val actions = root["actions"]?.jsonArray ?: return Triple("", "", "")

            var headerRenderer = actions.firstOrNull()
                ?.jsonObject?.get("openPopupAction")
                ?.jsonObject?.get("popup")
                ?.jsonObject?.get("multiPageMenuRenderer")
                ?.jsonObject?.get("header")
                ?.jsonObject?.get("activeAccountHeaderRenderer")
                ?.jsonObject

            // Alternative path for some API versions
            if (headerRenderer == null) {
                headerRenderer = root["header"]
                    ?.jsonObject?.get("activeAccountHeaderRenderer")
                    ?.jsonObject
            }

            if (headerRenderer == null) return Triple("", "", "")

            val name = headerRenderer["accountName"]
                ?.jsonObject?.get("runs")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.contentOrNull ?: ""

            val email = headerRenderer["email"]
                ?.jsonObject?.get("runs")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.contentOrNull ?: ""

            val thumbnails = headerRenderer["accountPhoto"]
                ?.jsonObject?.get("thumbnails")
                ?.jsonArray

            val avatarUrl = thumbnails?.lastOrNull()
                ?.jsonObject?.get("url")
                ?.jsonPrimitive?.contentOrNull ?: ""

            Triple(name, email, avatarUrl)
        } catch (e: Exception) {
            printe("Could not parse account info: ${e.message}", exception = e)
            Triple("", "", "")
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
