package ca.ilianokokoro.umihi.music.ui.screens.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.models.Cookies
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // Account fields are routed through the ViewModel so the profile screen has a
    // single source of truth (no second DatastoreRepository instance in the screen)
    // and can never show stale values while a fetch is updating them.
    private val _accountName = MutableStateFlow("")
    val accountName: StateFlow<String> = _accountName.asStateFlow()

    private val _accountEmail = MutableStateFlow("")
    val accountEmail: StateFlow<String> = _accountEmail.asStateFlow()

    private val _accountAvatarUrl = MutableStateFlow("")
    val accountAvatarUrl: StateFlow<String> = _accountAvatarUrl.asStateFlow()

    private val _cookies = MutableStateFlow(Cookies())
    val cookies: StateFlow<Cookies> = _cookies.asStateFlow()

    /** True while a profile fetch is in flight — drives the hero-header skeleton. */
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Count of fully-downloaded songs, kept live via a Room Flow. */
    private val _downloadedSongCount = MutableStateFlow(0)
    val downloadedSongCount: StateFlow<Int> = _downloadedSongCount.asStateFlow()

    /** Human-readable label of the current download location (\"Internal Storage\" / \"SD Card\"). */
    private val _downloadLocation = MutableStateFlow("")
    val downloadLocation: StateFlow<String> = _downloadLocation.asStateFlow()

    /**
     * In-memory guard so a fetch that already ran this session (success or
     * failure) is not silently re-tried every time the profile screen opens.
     */
    private var hasAttemptedRefresh = false

    init {
        viewModelScope.launch {
            datastoreRepository.accountName.collect { _accountName.value = it }
        }
        viewModelScope.launch {
            datastoreRepository.accountEmail.collect { _accountEmail.value = it }
        }
        viewModelScope.launch {
            datastoreRepository.accountAvatarUrl.collect { _accountAvatarUrl.value = it }
        }
        viewModelScope.launch {
            datastoreRepository.cookies.collect { _cookies.value = it }
        }
        viewModelScope.launch {
            AppDatabase.getInstance(application)
                .songRepository()
                .observeDownloadedSongs()
                .collect { songs -> _downloadedSongCount.value = songs.size }
        }
        viewModelScope.launch {
            datastoreRepository.settings.collect { settings ->
                val label = UmihiHelper.getAvailableStorageOptions(application)
                    .firstOrNull { it.second == settings.downloadPath }?.first
                _downloadLocation.value = label ?: ""
            }
        }
    }

    /**
     * Fetches and stores profile data (name, email, avatar) if the user is logged in
     * but one or more profile fields are missing. Safe to call on every
     * ProfileScreen open, including sessions created before avatar caching.
     */
    fun refreshProfileIfNeeded() {
        // Already in flight (e.g. the user navigated back and forward quickly
        // before the first call resolved) — don't start a duplicate fetch.
        if (isRefreshing.value) return
        // A fetch already ran this app session (success or failure). Don't
        // silently retry on every screen open.
        if (hasAttemptedRefresh) return

        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val name = datastoreRepository.accountName.first()
                val email = datastoreRepository.accountEmail.first()
                val avatarUrl = datastoreRepository.accountAvatarUrl.first()
                val cookies = datastoreRepository.cookies.first()
                if (cookies.isEmpty()) return@launch
                if (name.isNotBlank() && email.isNotBlank() && avatarUrl.isNotBlank()) {
                    return@launch
                }

                val settings = datastoreRepository.getSettings()
                fetchAndSaveAccountInfo(settings)
            } catch (e: Exception) {
                printe("ProfileViewModel: failed to check/refresh profile: ${e.message}", exception = e)
            } finally {
                hasAttemptedRefresh = true
                _isRefreshing.value = false
            }
        }
    }

    /** Clears the session and profile data. Suspends until persisted. */
    suspend fun logOut() {
        datastoreRepository.logOut()
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
