package ca.ilianokokoro.umihi.music.ui.screens.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val datastoreRepository = DatastoreRepository(application)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetches and stores profile data (name, email, avatar) if the user is logged in
     * but the data has not been saved yet. Safe to call on every ProfileScreen open.
     */
    fun refreshProfileIfNeeded() {
        viewModelScope.launch {
            try {
                val name = datastoreRepository.accountName.first()
                val cookies = datastoreRepository.cookies.first()
                if (cookies.isEmpty() || name.isNotBlank()) return@launch

                val settings = datastoreRepository.getSettings()
                fetchAndSaveAccountInfo(settings)
            } catch (e: Exception) {
                printe("ProfileViewModel: failed to check/refresh profile: ${e.message}", exception = e)
            }
        }
    }

    private suspend fun fetchAndSaveAccountInfo(settings: UmihiSettings) {
        try {
            val responseJson = YoutubeApiClient.getAccountMenu(settings)
            val (name, email, avatarUrl) = parseAccountInfo(responseJson)
            if (name.isNotBlank() || email.isNotBlank() || avatarUrl.isNotBlank()) {
                datastoreRepository.saveAccountInfo(name, email, avatarUrl)
            }
        } catch (e: Exception) {
            printe("ProfileViewModel: failed to fetch account info: ${e.message}", exception = e)
        }
    }

    /**
     * Parses the account_menu API response.
     * Returns Triple(name, email, avatarUrl) — fields are blank on parse failure.
     */
    private fun parseAccountInfo(jsonString: String): Triple<String, String, String> {
        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val actions = root["actions"]?.jsonArray

            var headerRenderer = actions?.firstOrNull()
                ?.jsonObject?.get("openPopupAction")
                ?.jsonObject?.get("popup")
                ?.jsonObject?.get("multiPageMenuRenderer")
                ?.jsonObject?.get("header")
                ?.jsonObject?.get("activeAccountHeaderRenderer")
                ?.jsonObject

            // Alternative path used by some API versions
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

            val avatarUrl = headerRenderer["accountPhoto"]
                ?.jsonObject?.get("thumbnails")
                ?.jsonArray?.lastOrNull()
                ?.jsonObject?.get("url")
                ?.jsonPrimitive?.contentOrNull ?: ""

            Triple(name, email, avatarUrl)
        } catch (e: Exception) {
            printe("ProfileViewModel: could not parse account info: ${e.message}", exception = e)
            Triple("", "", "")
        }
    }

    companion object {
        fun Factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProfileViewModel(application) }
        }
    }
}
