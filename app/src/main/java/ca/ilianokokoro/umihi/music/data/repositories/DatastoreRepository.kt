package ca.ilianokokoro.umihi.music.data.repositories

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper.isNullOrInvalidId
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.COOKIES
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.DATA_SYNC_ID
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.DOWNLOAD_ON_METERED
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.DOWNLOAD_PATH
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.KEEP_SCREEN_ON
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.SHOW_PODCAST_PLAYLIST
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.USE_AUDIO_OFFLOAD
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.USE_SPECIAL_LANGUAGE
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.AUTO_CHECK_UPDATES
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.DISMISSED_UPDATE_VERSION
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.LAST_UPDATE_CHECK_MS
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys.LAST_UPDATE_RESPONSE
import ca.ilianokokoro.umihi.music.models.Cookies
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = Constants.Datastore.NAME)

class DatastoreRepository(private val context: Context) {
    object PreferenceKeys {
        val COOKIES = stringPreferencesKey(Constants.Datastore.COOKIES_KEY)
        val DATA_SYNC_ID = stringPreferencesKey(Constants.Datastore.DATA_SYNC_ID)
        val SHOW_PODCAST_PLAYLIST = booleanPreferencesKey(Constants.Datastore.SHOW_PODCAST_PLAYLIST)
        val USE_SPECIAL_LANGUAGE = booleanPreferencesKey(Constants.Datastore.USE_SPECIAL_LANGUAGE)
        val USE_AUDIO_OFFLOAD = booleanPreferencesKey(Constants.Datastore.USE_AUDIO_OFFLOAD)
        val KEEP_SCREEN_ON = booleanPreferencesKey(Constants.Datastore.KEEP_SCREEN_ON)
        val DOWNLOAD_ON_METERED = booleanPreferencesKey(Constants.Datastore.DOWNLOAD_ON_METERED)
        val DOWNLOAD_PATH = stringPreferencesKey(Constants.Datastore.DOWNLOAD_PATH)
        val WELCOME_SHOWN = booleanPreferencesKey(Constants.Datastore.WELCOME_SHOWN)
        val ACCOUNT_NAME = stringPreferencesKey(Constants.Datastore.ACCOUNT_NAME)
        val ACCOUNT_EMAIL = stringPreferencesKey(Constants.Datastore.ACCOUNT_EMAIL)
        val ACCOUNT_AVATAR_URL = stringPreferencesKey(Constants.Datastore.ACCOUNT_AVATAR_URL)
        val LAST_SONG_ID = stringPreferencesKey(Constants.Datastore.LAST_SONG_ID)
        val LAST_POSITION_MS = longPreferencesKey(Constants.Datastore.LAST_POSITION_MS)
        val AUTO_CHECK_UPDATES = booleanPreferencesKey(Constants.Datastore.AUTO_CHECK_UPDATES)
        val DISMISSED_UPDATE_VERSION =
            stringPreferencesKey(Constants.Datastore.DISMISSED_UPDATE_VERSION)
        val LAST_UPDATE_CHECK_MS = longPreferencesKey(Constants.Datastore.LAST_UPDATE_CHECK_MS)
        val LAST_UPDATE_RESPONSE = stringPreferencesKey(Constants.Datastore.LAST_UPDATE_RESPONSE)
    }

    suspend fun <T> save(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit {
            it[key] = value
        }
    }

    val settings = context.dataStore.data.map {
        val showPodcastPlaylist = it[SHOW_PODCAST_PLAYLIST] ?: true
        val useSpecialLanguage = it[USE_SPECIAL_LANGUAGE] ?: false
        val useAudioOffload = it[USE_AUDIO_OFFLOAD] ?: false
        val keepScreenOn = it[KEEP_SCREEN_ON] ?: false
        val downloadOnMetered = it[DOWNLOAD_ON_METERED] ?: false
        val downloadPath = it[DOWNLOAD_PATH]
        val autoCheckForUpdates = it[AUTO_CHECK_UPDATES] ?: true
        val dismissedUpdateVersion = it[DISMISSED_UPDATE_VERSION]
        val cookies = cookies.first()
        val dataSyncId = dataSyncId.first()

        UmihiSettings(
            showPodcastPlaylist = showPodcastPlaylist,
            cookies = cookies,
            dataSyncId = dataSyncId,
            useSpecialLanguage = useSpecialLanguage,
            useAudioOffload = useAudioOffload,
            keepScreenOn = keepScreenOn,
            downloadOnMetered = downloadOnMetered,
            downloadPath = downloadPath,
            autoCheckForUpdates = autoCheckForUpdates,
            dismissedUpdateVersion = dismissedUpdateVersion
        )
    }

    suspend fun getSettings(): UmihiSettings {
        return settings.first()
    }

    val cookies = context.dataStore.data.map {
        Cookies(it[COOKIES] ?: String())
    }

    val dataSyncId: Flow<String?> = flow {
        context.dataStore.data.collect { prefs ->
            val id = prefs[DATA_SYNC_ID]
            if (id.isNullOrInvalidId()) {
                if (id != null) {
                    context.dataStore.edit { it.remove(DATA_SYNC_ID) }
                }
                emit(null)
            } else {
                emit(id)
            }
        }
    }

    suspend fun saveCookies(cookies: Cookies) {
        context.dataStore.edit {
            it[COOKIES] = cookies.toRawCookie()
        }
    }

    suspend fun logOut() {
        saveCookies(Cookies())
        saveDataSyncId("")
        clearAccountInfo()
    }

    suspend fun saveDataSyncId(newId: String) {
        if (newId.isNullOrInvalidId()) {
            context.dataStore.edit { it.remove(DATA_SYNC_ID) }
            return
        }
        context.dataStore.edit {
            it[DATA_SYNC_ID] = newId
        }
    }

    suspend fun saveDownloadPath(path: String?) {
        context.dataStore.edit {
            if (path.isNullOrBlank()) {
                it.remove(DOWNLOAD_PATH)
            } else {
                it[DOWNLOAD_PATH] = path
            }
        }
    }

    suspend fun hasSeenWelcome(): Boolean {
        return context.dataStore.data.first()[PreferenceKeys.WELCOME_SHOWN] == true
    }

    suspend fun markWelcomeSeen() {
        context.dataStore.edit { it[PreferenceKeys.WELCOME_SHOWN] = true }
    }

    // ── Account profile info ──────────────────────────────────────────────────

    val accountName = context.dataStore.data.map { it[PreferenceKeys.ACCOUNT_NAME] ?: "" }
    val accountEmail = context.dataStore.data.map { it[PreferenceKeys.ACCOUNT_EMAIL] ?: "" }
    val accountAvatarUrl = context.dataStore.data.map { it[PreferenceKeys.ACCOUNT_AVATAR_URL] ?: "" }

    suspend fun saveAccountInfo(name: String, email: String, avatarUrl: String) {
        context.dataStore.edit { prefs ->
            if (name.isNotBlank()) prefs[PreferenceKeys.ACCOUNT_NAME] = name
            if (email.isNotBlank()) prefs[PreferenceKeys.ACCOUNT_EMAIL] = email
            if (avatarUrl.isNotBlank()) prefs[PreferenceKeys.ACCOUNT_AVATAR_URL] = avatarUrl
        }
    }

    suspend fun clearAccountInfo() {
        context.dataStore.edit { prefs ->
            prefs.remove(PreferenceKeys.ACCOUNT_NAME)
            prefs.remove(PreferenceKeys.ACCOUNT_EMAIL)
            prefs.remove(PreferenceKeys.ACCOUNT_AVATAR_URL)
        }
    }

    // ── Last playback state ───────────────────────────────────────────────────

    /**
     * Persists the song ID and seek position so the user can optionally resume
     * after the app is closed via the Recents screen.
     */
    suspend fun saveLastPlaybackState(songId: String, positionMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[PreferenceKeys.LAST_SONG_ID] = songId
            prefs[PreferenceKeys.LAST_POSITION_MS] = positionMs
        }
    }

    /**
     * Returns the last saved (songId, positionMs) pair.
     * Both values are null / 0 if nothing has been saved yet.
     */
    suspend fun getLastPlaybackState(): Pair<String?, Long> {
        val prefs = context.dataStore.data.first()
        return prefs[PreferenceKeys.LAST_SONG_ID] to (prefs[PreferenceKeys.LAST_POSITION_MS] ?: 0L)
    }

    // ── App auto-update state ──────────────────────────────────────────────────

    /** Timestamp (ms since epoch) of the last successful update check, or 0 if never checked. */
    suspend fun getLastUpdateCheckMs(): Long {
        return context.dataStore.data.first()[PreferenceKeys.LAST_UPDATE_CHECK_MS] ?: 0L
    }

    suspend fun saveLastUpdateCheckMs(ms: Long) {
        context.dataStore.edit { it[PreferenceKeys.LAST_UPDATE_CHECK_MS] = ms }
    }

    /**
     * Remembers a version the user chose to skip. A blank/null value clears it
     * so the popup can resurface for that version again.
     */
    suspend fun saveDismissedUpdateVersion(version: String?) {
        context.dataStore.edit { prefs ->
            if (version.isNullOrBlank()) {
                prefs.remove(PreferenceKeys.DISMISSED_UPDATE_VERSION)
            } else {
                prefs[PreferenceKeys.DISMISSED_UPDATE_VERSION] = version
            }
        }
    }

    /**
     * The raw GitHub `/releases/latest` response from the last successful
     * check, or null if the check has never succeeded. Used as an offline /
     * rate-limited fallback so the update popup keeps working and the manual
     * check never surfaces an error.
     */
    suspend fun getLastUpdateResponse(): String? {
        return context.dataStore.data.first()[PreferenceKeys.LAST_UPDATE_RESPONSE]
    }

    suspend fun saveLastUpdateResponse(body: String?) {
        context.dataStore.edit { prefs ->
            if (body.isNullOrBlank()) {
                prefs.remove(PreferenceKeys.LAST_UPDATE_RESPONSE)
            } else {
                prefs[PreferenceKeys.LAST_UPDATE_RESPONSE] = body
            }
        }
    }

    suspend fun debugPrintAllPreferences() {
        val prefs = context.dataStore.data.first()
        LogHelper.printd("=== All preferences ===")
        prefs.asMap().forEach { (key, value) ->
            LogHelper.printd("  $key = $value")
        }
        LogHelper.printd("========================")
    }
}
