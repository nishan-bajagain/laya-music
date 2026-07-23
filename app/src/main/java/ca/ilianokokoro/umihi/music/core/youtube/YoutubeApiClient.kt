package ca.ilianokokoro.umihi.music.core.youtube

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeAuthHelper.applyHeaders
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Privacy
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object YoutubeApiClient {
    suspend fun browse(browseId: String, settings: UmihiSettings, fields: String? = null): String {
        return requestWithContext(
            url = Constants.YoutubeApi.Browse.URL,
            idName = "browseId",
            id = browseId,
            settings = settings,
            fields = fields,
        )
    }

    suspend fun requestContinuation(
        continuationToken: String,
        settings: UmihiSettings,
        fields: String? = null,
    ): String {
        return requestWithContext(
            url = Constants.YoutubeApi.Browse.URL,
            idName = "continuation",
            id = continuationToken,
            settings = settings,
            fields = fields,
        )
    }


    suspend fun createPlaylist(
        title: String,
        description: String,
        privacy: Privacy,
        songs: List<Song> = listOf(),
        settings: UmihiSettings
    ): String {
        val baseBody = YoutubeAuthHelper.buildContextBody(
            idName = null,
            id = null,
            settings = settings
        )

        val body = buildJsonObject {
            baseBody.forEach { (key, value) ->
                put(key, value)
            }

            put("title", title)
            put("description", description)
            put("privacyStatus", privacy.value)

            put(
                "videoIds",
                buildJsonArray {
                    songs.forEach { add(it.youtubeId) }
                }
            )
        }

        return requestWithBody(
            url = Constants.YoutubeApi.Create.URL,
            body = body,
            settings = settings
        )
    }

    suspend fun deletePlaylist(
        playlist: PlaylistInfo,
        settings: UmihiSettings
    ): String {
        val baseBody = YoutubeAuthHelper.buildContextBody(
            idName = null,
            id = null,
            settings = settings
        )

        val playlistId = playlist.id.removePrefix("VL")

        val body = buildJsonObject {
            baseBody.forEach { (key, value) ->
                put(key, value)
            }

            put("playlistId", playlistId)
        }


        return requestWithBody(
            url = Constants.YoutubeApi.Delete.URL,
            body = body,
            settings = settings
        )
    }


    suspend fun getPlayerInfo(
        videoId: String,
        client: JsonObject? = null,
        visitorData: String? = null,
        settings: UmihiSettings? = null,
        fields: String? = null,
        /**
         * When true, routes the request through music.youtube.com instead of www.youtube.com.
         * Use this for WEB_REMIX tracking requests so the SAPISIDHASH origin matches the
         * cookie domain and plays count toward YouTube Music watch history.
         */
        musicOrigin: Boolean = false,
    ): String {
        val url = if (musicOrigin) {
            Constants.YoutubeApi.PlayerInfo.MUSIC_URL
        } else {
            Constants.YoutubeApi.PlayerInfo.URL
        }
        return requestWithContext(
            url = url,
            idName = "videoId",
            id = videoId,
            settings = settings,
            client = client,
            visitorData = visitorData,
            fields = fields,
        )
    }

    suspend fun setLike(
        videoId: String,
        liked: Boolean,
        settings: UmihiSettings
    ): String {
        val baseBody = YoutubeAuthHelper.buildContextBody(
            idName = null,
            id = null,
            settings = settings
        )

        val body = buildJsonObject {
            baseBody.forEach { (key, value) ->
                put(key, value)
            }

            put(
                "target",
                buildJsonObject {
                    put("videoId", videoId)
                }
            )
        }

        val url = if (liked) {
            Constants.YoutubeApi.Like.LIKE_URL
        } else {
            Constants.YoutubeApi.Like.REMOVE_LIKE_URL
        }

        return requestWithBody(
            url = url,
            body = body,
            settings = settings
        )
    }

    suspend fun next(videoId: String, settings: UmihiSettings? = null): String {
        return requestWithContext(
            url = Constants.YoutubeApi.Next.URL,
            idName = "videoId",
            id = videoId,
            settings = settings
        )
    }

    suspend fun editPlaylist(
        playlistId: String,
        videoId: String,
        settings: UmihiSettings
    ): String {
        val baseBody = YoutubeAuthHelper.buildContextBody(
            idName = null,
            id = null,
            settings = settings
        )

        val cleanPlaylistId = playlistId.removePrefix("VL")

        val body = buildJsonObject {
            baseBody.forEach { (key, value) ->
                put(key, value)
            }
            put("playlistId", cleanPlaylistId)
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("addedVideoId", videoId)
                    put("action", "ACTION_ADD_VIDEO")
                })
            })
        }

        return requestWithBody(
            url = Constants.YoutubeApi.Playlist.EDIT_URL,
            body = body,
            settings = settings
        )
    }

    /**
     * Remove [videoId] from the YouTube Music playlist identified by [playlistId].
     * Uses the ACTION_REMOVE_VIDEO action of the internal browse/edit_playlist endpoint.
     *
     * @param setVideoId  The per-entry token (`playlistSetVideoId`) from the playlist response.
     *                    Required by YT Music to identify the exact entry to remove; without it
     *                    the API silently accepts the request but removes nothing.
     */
    suspend fun removeFromPlaylist(
        playlistId: String,
        videoId: String,
        setVideoId: String? = null,
        settings: UmihiSettings
    ): String {
        val baseBody = YoutubeAuthHelper.buildContextBody(
            idName = null,
            id = null,
            settings = settings
        )

        val cleanPlaylistId = playlistId.removePrefix("VL")

        val body = buildJsonObject {
            baseBody.forEach { (key, value) ->
                put(key, value)
            }
            put("playlistId", cleanPlaylistId)
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    if (setVideoId != null) put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                    put("action", "ACTION_REMOVE_VIDEO")
                })
            })
        }

        return requestWithBody(
            url = Constants.YoutubeApi.Playlist.EDIT_URL,
            body = body,
            settings = settings
        )
    }

    suspend fun search(query: String): String {
        return requestWithContext(
            url = Constants.YoutubeApi.Search.URL,
            idName = "query",
            id = query
        )
    }

    /**
     * Fetches the YouTube Music account menu which contains profile name, email, and avatar.
     * Requires valid auth cookies. Returns raw JSON string.
     */
    suspend fun getAccountMenu(settings: UmihiSettings): String {
        val body = YoutubeAuthHelper.buildContextBody(
            idName = null,
            id = null,
            settings = settings
        )
        return requestWithBody(
            url = Constants.YoutubeApi.Account.MENU_URL,
            body = body,
            settings = settings
        )
    }

    private suspend fun requestWithBody(
        url: String,
        body: Any,
        settings: UmihiSettings? = null,
        client: JsonObject? = null,
        visitorData: String? = null,
        fields: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val requestBody = body
            .toString()
            .toRequestBody(mediaType)

        val httpUrl = if (fields != null) {
            url.toHttpUrl().newBuilder()
                .addQueryParameter("\$fields", fields)
                .build()
        } else {
            url.toHttpUrl()
        }

        val request = Request.Builder()
            .url(httpUrl)
            .post(requestBody)
            .applyHeaders(httpUrl, settings, visitorData, client)
            .build()

        UmihiHttpClient.client
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    val bodyPreview = response.body?.string()?.take(500) ?: "null"
                    throw IOException(
                        "HTTP ${response.code}: ${response.message} — body=$bodyPreview"
                    )
                }

                response.body?.string()
                    ?: throw IOException("Empty response body")
            }
    }

    private suspend fun requestWithContext(
        url: String,
        idName: String,
        id: String,
        settings: UmihiSettings? = null,
        client: JsonObject? = null,
        visitorData: String? = null,
        fields: String? = null,
    ): String {
        val body = YoutubeAuthHelper.buildContextBody(
            idName,
            id,
            settings,
            client,
            visitorData
        )

        return requestWithBody(
            url = url,
            body = body,
            settings = settings,
            client = client,
            visitorData = visitorData,
            fields = fields,
        )
    }
}