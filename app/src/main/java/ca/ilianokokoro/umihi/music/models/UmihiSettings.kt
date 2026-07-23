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
    val sendPlaybackData: Boolean = false,
    val downloadOnMetered: Boolean = false,
    /** Absolute path to the root download directory, or null to use internal storage. */
    val downloadPath: String? = null,
) {
    val canTrack: Boolean get() = sendPlaybackData && !cookies.isEmpty()
}
