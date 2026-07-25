package ca.ilianokokoro.umihi.music.core

import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object Constants {

    object Downloads {
        const val MAX_CONCURRENT_DOWNLOADS = 8
        const val DIRECTORY = "downloads"
        const val THUMBNAILS_FOLDER = "thumbnails_downloads"
        const val AUDIO_FILES_FOLDER = "audio_files_downloads"
        const val DOWNLOADED_PLAYLIST_ID = "_downloaded_"
    }

    object Locale {
        object Special {
            const val CODE = "eo"
            const val CLICK_QUANTITY = 25
        }
    }

    object Ui {
        object MiniPlayer {
            val HEIGHT = 70.dp
        }

        val SCROLLABLE_BOTTOM_PADDING = 200.dp
        const val WEAROS_MAX_IMAGE_SIZE = 720

        object Player {
            object SleepTimer {
                const val DEFAULT_VALUE = 20
                const val STEP_VALUE = 5
                const val STEP_AMOUNT = 40
            }
        }
    }

    object Animation {
        const val NAVIGATION_DURATION = 200
        const val IMAGE_FADE_DURATION = 200
    }

    object Auth {
        const val START_URL =
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F%26feature%3D__FEATURE__&hl=en"
        const val END_URL =
            "https://music.youtube.com/"
    }

    object Datastore {
        const val NAME = "music-app"
        const val COOKIES_KEY = "cookies"
        const val DATA_SYNC_ID = "data-sync-id"
        const val SHOW_PODCAST_PLAYLIST = "show-podcast-playlist"
        const val USE_SPECIAL_LANGUAGE = "use-special-language"
        const val USE_AUDIO_OFFLOAD = "use-audio-offload"
        const val KEEP_SCREEN_ON = "keep-screen-on"
        const val SEND_PLAYBACK_DATA = "send-playback-data"
        const val DOWNLOAD_ON_METERED = "download-on-metered"
        const val DOWNLOAD_PATH = "download-path"
        const val WELCOME_SHOWN = "welcome-shown"
        const val ACCOUNT_NAME = "account-name"
        const val ACCOUNT_EMAIL = "account-email"
        const val ACCOUNT_AVATAR_URL = "account-avatar-url"
        // Last playback state — saved on task removal so the user can resume later
        const val LAST_SONG_ID = "last-song-id"
        const val LAST_POSITION_MS = "last-position-ms"
        // Global lyrics timing offset (ms) — user-adjustable from the lyrics screen
        const val LYRICS_GLOBAL_OFFSET = "lyrics-global-offset"
    }

    object Database {
        const val NAME = "music-app"
        const val VERSION = 9
        const val SONGS_TABLE = "songs"
        const val PLAYLISTS_TABLE = "playlists"
        const val LYRICS_TABLE = "lyrics"
    }

    object Lyrics {
        // LRCLIB public API
        const val LRCLIB_BASE_URL = "https://lrclib.net/api"
        const val LRCLIB_SEARCH_URL = "$LRCLIB_BASE_URL/search"
        const val LRCLIB_GET_URL = "$LRCLIB_BASE_URL/get"

        // Better Lyrics API
        const val BETTER_LYRICS_BASE_URL = "https://better-lyrics.boidu.dev"
        const val BETTER_LYRICS_SEARCH_URL = "$BETTER_LYRICS_BASE_URL/api/lyrics"

        const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000         // 7 days for positive results
        const val NEGATIVE_CACHE_TTL_MS = 30L * 60 * 1000         // 30 min for "not found" — short so re-open retries sooner
        const val FETCH_TIMEOUT_MS = 20_000L
        const val SYNC_RESUME_DELAY_MS = 3_000L

        // Retry / back-off
        const val RETRY_MAX_ATTEMPTS = 3
        const val RETRY_INITIAL_DELAY_MS = 1_000L

        // Track-change debounce — prevents firing a request on rapid skip spam
        const val SONG_CHANGE_DEBOUNCE_MS = 300L

        // Offset clamp range
        const val OFFSET_MIN_MS = -5_000L
        const val OFFSET_MAX_MS = 5_000L
        const val OFFSET_STEP_MS = 100L
    }

    object ExoPlayer {
        val AUDIO_MIME_MAP = mapOf(
            // AAC
            "audio/mp4a-latm" to "aac",
            "audio/aac" to "aac",
            "audio/x-aac" to "aac",
            "audio/vnd.dolby.heaac.1" to "aac",
            "audio/vnd.dolby.heaac.2" to "aac",

            // MP3
            "audio/mpeg" to "mp3",

            // M4A / MP4 audio
            "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a",

            // FLAC
            "audio/flac" to "flac",
            "audio/x-flac" to "flac",

            // Opus
            "audio/opus" to "opus",

            // Ogg
            "audio/ogg" to "ogg",
            "audio/vorbis" to "ogg",

            // WAV / PCM
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/vnd.wave" to "wav",
            "audio/raw" to "wav",

            // ALAC
            "audio/alac" to "alac",
            "audio/x-alac" to "alac",

            // Dolby
            "audio/ac3" to "ac3",
            "audio/eac3" to "eac3",
            "audio/eac3-joc" to "eac3",
            "audio/true-hd" to "thd",

            // DTS
            "audio/vnd.dts" to "dts",
            "audio/vnd.dts.hd" to "dtshd",

            // WebM
            "audio/webm" to "webm",

            // AMR
            "audio/amr" to "amr",
            "audio/amr-wb" to "awb",

            // 3GPP
            "audio/3gpp" to "3gp",
            "audio/3gpp2" to "3g2",

            // Windows Media Audio
            "audio/x-ms-wma" to "wma",

            // AIFF
            "audio/x-aiff" to "aif",

            // Misc legacy codecs
            "audio/evrc" to "evrc",
            "audio/qcelp" to "qcp",
            "audio/x-ima-adpcm" to "ima",
        )

        object Cache {
            const val NAME = "music-app-exoplayer"
            const val SIZE: Long = 1000L * 1024L * 1024L // 1000 MB
        }

        object Library {
            const val ROOT_ID = "root"
            const val PLAYLIST_ROOT = "root_playlist"
            const val PLAYLIST_PREFIX = "playlist:"
            const val PLAY_PLAYLIST_PREFIX = "action_play_playlist_"
            const val SHUFFLE_PLAYLIST_PREFIX = "action_shuffle_playlist_"
        }

        object SongMetadata {
            const val DURATION = "duration"
            const val UID = "uid"
            const val IS_EXPLICIT = "is-explicit"
            const val IS_LIKED = "is-liked"
        }
    }

    object Player {
        const val PROGRESS_UPDATE_DELAY = 150
        const val IMAGE_TRANSITION_DELAY = 200
        val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 2f, 3f, 5f)

        object Tracking {
            const val WATCHTIME_INTERVAL_MS = 15_000L
            const val WATCHTIME_ADVANCE_SEC = 20f
            const val POSITION_TOLERANCE_SEC = 1.5f
        }
    }

    object YoutubeApi {
        const val URL_REGEX =
            """https?://(www\.)?(youtube\.com|youtu\.be|music\.youtube\.com)/\S+"""
        const val RETRY_COUNT = 3
        const val PODCAST_PLAYLIST_ID = "VLSE"
        const val RETRY_DELAY = 1000
        const val YOUTUBE_URL_PREFIX = "https://www.youtube.com/watch?v="
        const val ORIGIN = "https://music.youtube.com"
        const val API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        object Browse {
            const val URL = "${ORIGIN}/youtubei/v1/browse?key=${API_KEY}&prettyPrint=false"
            const val PLAYLIST_BROWSE_ID = "FEmusic_liked_playlists"
            const val HOME_BROWSE_ID = "FEmusic_home"
        }

        object Next {
            const val URL = "${ORIGIN}/youtubei/v1/next?prettyPrint=false"
        }

        object Playlist {
            const val EDIT_URL = "${ORIGIN}/youtubei/v1/browse/edit_playlist?key=${API_KEY}&prettyPrint=false"
        }

        object Client {
            val WEB_REMIX =
                buildJsonObject {
                    put("clientName", JsonPrimitive("WEB_REMIX"))
                    put("clientVersion", JsonPrimitive("1.20250212.01.00"))
                    put("userAgent", JsonPrimitive(USER_AGENT))
                    put("xClientName", JsonPrimitive("67"))
                }

            val ANDROID_VR = buildJsonObject {
                put("clientName", JsonPrimitive("ANDROID_VR"))
                put("clientVersion", JsonPrimitive("1.61.48"))
                put("clientId", JsonPrimitive("28"))
                put(
                    "userAgent",
                    JsonPrimitive(
                        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
                    )
                )
                put("osName", JsonPrimitive("Android"))
                put("osVersion", JsonPrimitive("12"))
                put("deviceMake", JsonPrimitive("Oculus"))
                put("deviceModel", JsonPrimitive("Quest 3"))
                put("androidSdkVersion", JsonPrimitive("32"))
                put("buildId", JsonPrimitive("SQ3A.220605.009.A1"))
                put("cronetVersion", JsonPrimitive("132.0.6808.3"))
                put("packageName", JsonPrimitive("com.google.android.apps.youtube.vr.oculus"))
            }
        }

        object Create {
            const val URL = "${ORIGIN}/youtubei/v1/playlist/create?key=${API_KEY}&prettyPrint=false"
        }

        object Delete {
            const val URL = "${ORIGIN}/youtubei/v1/playlist/delete?key=${API_KEY}&prettyPrint=false"
        }

        object PlayerInfo {
            // Used by ANDROID_VR stream resolver (www.youtube.com gives better stream access)
            const val URL =
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            // Used by WEB_REMIX tracking requests — must match the music.youtube.com origin
            // so that cookies, SAPISIDHASH, and onBehalfOfUser are evaluated correctly and
            // plays count toward YouTube Music watch history.
            const val MUSIC_URL =
                "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
        }

        object Like {
            const val LIKE_URL = "${ORIGIN}/youtubei/v1/like/like?prettyPrint=false"
            const val REMOVE_LIKE_URL = "${ORIGIN}/youtubei/v1/like/removelike?prettyPrint=false"
        }

        object Account {
            const val MENU_URL = "${ORIGIN}/youtubei/v1/account/account_menu?key=${API_KEY}&prettyPrint=false"
        }

        object Search {
            const val URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
            const val FILTER = "EgWKAQIIAWoSEAMQBBAQEAUQFRAKEAkQERAO"
        }
    }
}
