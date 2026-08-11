package ca.ilianokokoro.umihi.music.models

import androidx.compose.runtime.Immutable

@Immutable
data class UmihiSettings(
    val cookies: Cookies = Cookies(),
    val dataSyncId: String? = null,
    val showPodcastPlaylist: Boolean = true,
    val useSpecialLanguage: Boolean = false,
    val useAudioOffload: Boolean = false,
    val keepScreenOn: Boolean = false,
    val downloadOnMetered: Boolean = false,
    /** Absolute path to the root download directory, or null to use internal storage. */
    val downloadPath: String? = null,
    val autoCheckForUpdates: Boolean = true,
    /** Version the user chose to skip — the update popup must not resurface for it. */
    val dismissedUpdateVersion: String? = null,
)
