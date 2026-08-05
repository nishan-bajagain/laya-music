package ca.ilianokokoro.umihi.music.data.datasources

import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * YouTube Music plain lyrics only.
 *
 * This source intentionally never scans timed cue renderers. LRCLIB owns all
 * synchronization so a changing YouTube response shape cannot downgrade or
 * corrupt synced lyrics.
 */
class YtmPlainLyricsDataSource {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    sealed class Outcome {
        data class Found(val text: String) : Outcome()
        data object NotFound : Outcome()
        data class NetworkError(val cause: Throwable) : Outcome()
        data class Malformed(val cause: Throwable) : Outcome()
    }

    suspend fun getPlainLyrics(
        videoId: String,
        settings: UmihiSettings?
    ): Outcome = withContext(Dispatchers.IO) {
        val effectiveSettings = settings ?: UmihiSettings()
        val clients = listOf(
            Constants.YoutubeApi.Client.WEB_REMIX,
            Constants.YoutubeApi.Client.ANDROID_MUSIC
        )
        val browseIds = linkedSetOf<String>()
        var nextFailures = 0
        var browseFailures = 0
        var successfulResponses = 0
        var lastError: Throwable? = null

        try {
            for (client in clients) {
                try {
                    val nextJson = YoutubeApiClient.next(
                        videoId = videoId,
                        settings = effectiveSettings,
                        client = client
                    )
                    successfulResponses++
                    browseIds += extractLyricsBrowseIds(nextJson)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    nextFailures++
                    lastError = e
                    LogHelper.printd(
                        "YTM plain /next failed client=${client["clientName"]}: ${e.message}"
                    )
                }
            }

            if (browseIds.isEmpty()) {
                return@withContext if (nextFailures > 0 && successfulResponses == 0) {
                    Outcome.NetworkError(
                        lastError ?: IllegalStateException("YTM /next failed")
                    )
                } else {
                    LogHelper.printLyrics(
                        "Lyrics provider=ytm result=no-plain-lyrics-tab videoId=$videoId"
                    )
                    Outcome.NotFound
                }
            }

            for (browseId in browseIds) {
                for (client in clients) {
                    try {
                        val browseJson = YoutubeApiClient.browse(
                            browseId = browseId,
                            settings = effectiveSettings,
                            client = client
                        )
                        successfulResponses++
                        val text = extractLyricsText(browseJson)
                        if (!text.isNullOrBlank()) {
                            LogHelper.printLyrics(
                                "Lyrics provider=ytm result=plain-found videoId=$videoId"
                            )
                            return@withContext Outcome.Found(text.trim())
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        browseFailures++
                        lastError = e
                        LogHelper.printd(
                            "YTM plain /browse failed browseId=$browseId: ${e.message}"
                        )
                    }
                }
            }

            if (successfulResponses == 0 && (nextFailures + browseFailures) > 0) {
                Outcome.NetworkError(
                    lastError ?: IllegalStateException("YTM plain requests failed")
                )
            } else {
                Outcome.NotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Outcome.NetworkError(e)
        } catch (e: Exception) {
            Outcome.Malformed(e)
        }
    }

    private fun extractLyricsBrowseIds(nextJsonString: String): List<String> = try {
        val root = json.parseToJsonElement(nextJsonString)
        val tabs = root.jsonObject["contents"]?.jsonObject
            ?.get("singleColumnMusicWatchNextResultsRenderer")?.jsonObject
            ?.get("tabbedRenderer")?.jsonObject
            ?.get("watchNextTabbedResultsRenderer")?.jsonObject
            ?.get("tabs")?.jsonArray
            ?: return emptyList()

        tabs.mapNotNull { tab ->
            val renderer = tab.jsonObject["tabRenderer"]?.jsonObject
                ?: return@mapNotNull null
            val title = textFrom(renderer["title"]).orEmpty().lowercase()
            if (!isLyricsTitle(title)) return@mapNotNull null
            renderer["endpoint"]?.jsonObjectOrNull()
                ?.get("browseEndpoint")?.jsonObjectOrNull()
                ?.get("browseId")?.primitiveText()
        }
    } catch (e: Exception) {
        throw IllegalStateException("Malformed YTM /next response", e)
    }

    private fun extractLyricsText(browseJsonString: String): String? {
        val root = try {
            json.parseToJsonElement(browseJsonString)
        } catch (e: Exception) {
            throw IllegalStateException("Malformed YTM /browse response", e)
        }
        return listOf(
            "musicDescriptionShelfRenderer",
            "lyricsShelfRenderer",
            "lyricsRenderer"
        ).asSequence()
            .flatMap { key -> objectsWithKey(root, key).asSequence() }
            .mapNotNull { renderer ->
                textFrom(renderer["description"])
                    ?: textFrom(renderer["lyrics"])
                    ?: textFrom(renderer["text"])
            }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
    }

    internal fun extractPlainLyricsForTest(browseJsonString: String): String? =
        extractLyricsText(browseJsonString)

    private fun isLyricsTitle(title: String) =
        listOf("lyrics", "lyric", "paroles", "letra", "letras", "歌詞")
            .any(title::contains)

    private fun objectsWithKey(
        element: JsonElement,
        key: String
    ): List<JsonObject> {
        val found = mutableListOf<JsonObject>()
        when (element) {
            is JsonObject -> {
                element[key]?.jsonObjectOrNull()?.let(found::add)
                element.values.forEach { found += objectsWithKey(it, key) }
            }

            is JsonArray -> element.forEach { found += objectsWithKey(it, key) }
            else -> Unit
        }
        return found
    }

    private fun textFrom(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull(::textFrom).joinToString("")
        is JsonObject -> element["simpleText"]?.let(::textFrom)
            ?: element["text"]?.let(::textFrom)
            ?: element["runs"]?.let(::textFrom)
            ?: element["contents"]?.let(::textFrom)
    }

    private fun JsonElement.primitiveText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
}