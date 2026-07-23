package ca.ilianokokoro.umihi.music.data.datasources

import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Data source for YouTube Music's built-in lyrics endpoint.
 *
 * Flow:
 *  1. Call /next with videoId to find the Lyrics tab's browseId.
 *  2. Call /browse with that browseId to retrieve lyrics.
 *     - Tries timedLyricsRenderer first (synced LRC format with timestamps).
 *     - Falls back to musicDescriptionShelfRenderer (plain text).
 *
 * Works without authentication for publicly-available tracks; auth improves
 * coverage for region-locked or signed-in-only content.
 */
class YtmLyricsDataSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Result from YouTube Music lyrics fetch.
     * [syncedLrc] is an LRC-format string if timed lyrics are available;
     * [plainText] is a fallback plain string. Both may be null if unavailable.
     */
    data class YtmLyricsResult(
        val syncedLrc: String?,
        val plainText: String?
    )

    /**
     * Returns lyrics from YouTube Music for [videoId].
     * Prefers synced (timed) lyrics; falls back to plain text.
     * Returns null if no lyrics are found at all.
     */
    suspend fun getLyrics(videoId: String, settings: UmihiSettings?): YtmLyricsResult? =
        withContext(Dispatchers.IO) {
            try {
                val nextJson = YoutubeApiClient.next(videoId = videoId, settings = settings)
                val browseId = extractLyricsBrowseId(nextJson) ?: return@withContext null

                val browseJson = YoutubeApiClient.browse(
                    browseId = browseId,
                    settings = settings ?: UmihiSettings()
                )

                // Try synced (timed) lyrics first — only available for some tracks
                val synced = extractTimedLyrics(browseJson)
                if (!synced.isNullOrBlank()) {
                    LogHelper.printd("YTM: got synced lyrics for $videoId")
                    return@withContext YtmLyricsResult(syncedLrc = synced, plainText = null)
                }

                // Fall back to plain text
                val plain = extractLyricsText(browseJson)
                if (!plain.isNullOrBlank()) {
                    return@withContext YtmLyricsResult(syncedLrc = null, plainText = plain)
                }

                null
            } catch (e: Exception) {
                LogHelper.printe("YTM lyrics fetch failed for $videoId: ${e.message}", exception = e)
                null
            }
        }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    private fun extractLyricsBrowseId(nextJsonString: String): String? {
        return try {
            val root = json.parseToJsonElement(nextJsonString).jsonObject
            val tabs = root["contents"]
                ?.jsonObject?.get("singleColumnMusicWatchNextResultsRenderer")
                ?.jsonObject?.get("tabbedRenderer")
                ?.jsonObject?.get("watchNextTabbedResultsRenderer")
                ?.jsonObject?.get("tabs")
                ?.jsonArray ?: return null

            for (tab in tabs) {
                val tabRenderer = tab.jsonObject["tabRenderer"]?.jsonObject ?: continue
                val title = tabRenderer["title"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?: continue
                if (title == "lyrics" || title == "paroles" || title == "letra" ||
                    title == "歌詞" || title == "letras"
                ) {
                    return tabRenderer["endpoint"]
                        ?.jsonObject?.get("browseEndpoint")
                        ?.jsonObject?.get("browseId")
                        ?.jsonPrimitive?.contentOrNull
                }
            }
            null
        } catch (e: Exception) {
            LogHelper.printd("YTM: could not extract lyricsBrowseId — ${e.message}")
            null
        }
    }

    /**
     * Try to extract timed lyrics from a timedLyricsRenderer in the browse response.
     * Returns an LRC-format string, or null if not available.
     *
     * YTM response structure (when timed lyrics are present):
     *   contents.sectionListRenderer.contents[].timedLyricsRenderer
     *     .cueGroups[].lyricsCueGroupRenderer
     *       .cues[].lyricsCueRenderer
     *         .text.runs[].text    — the lyric line
     *         .startCueTime        — timestamp as "1.234s"
     */
    private fun extractTimedLyrics(browseJsonString: String): String? {
        return try {
            val root = json.parseToJsonElement(browseJsonString).jsonObject
            val contents = root["contents"]
                ?.jsonObject?.get("sectionListRenderer")
                ?.jsonObject?.get("contents")
                ?.jsonArray ?: return null

            for (section in contents) {
                val timedRenderer = section.jsonObject["timedLyricsRenderer"]?.jsonObject
                    ?: continue

                val cueGroups = timedRenderer["cueGroups"]?.jsonArray ?: continue
                val lrcLines = mutableListOf<Pair<Long, String>>() // (timeMs, text)

                for (group in cueGroups) {
                    val cues = group.jsonObject["lyricsCueGroupRenderer"]
                        ?.jsonObject?.get("cues")
                        ?.jsonArray ?: continue

                    for (cue in cues) {
                        val cueRenderer = cue.jsonObject["lyricsCueRenderer"]?.jsonObject
                            ?: continue

                        val startTimeStr = cueRenderer["startCueTime"]
                            ?.jsonPrimitive?.contentOrNull ?: continue
                        val timeMs = parseCueTimestamp(startTimeStr) ?: continue

                        val text = cueRenderer["text"]
                            ?.jsonObject?.get("runs")
                            ?.jsonArray
                            ?.joinToString("") { run ->
                                run.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            }?.trim() ?: continue

                        if (text.isNotBlank()) {
                            lrcLines.add(timeMs to text)
                        }
                    }
                }

                if (lrcLines.isNotEmpty()) {
                    return buildLrcString(lrcLines.sortedBy { it.first })
                }
            }
            null
        } catch (e: Exception) {
            LogHelper.printd("YTM: could not extract timed lyrics — ${e.message}")
            null
        }
    }

    /**
     * Parse a YouTube timed-text timestamp string (e.g. "1.234s", "90s") into milliseconds.
     */
    private fun parseCueTimestamp(timeStr: String): Long? {
        return try {
            val seconds = timeStr.trimEnd('s').toDouble()
            (seconds * 1000).toLong()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a standard LRC string from a list of (timeMs, text) pairs.
     * Format: [mm:ss.xx]text
     */
    private fun buildLrcString(lines: List<Pair<Long, String>>): String =
        lines.joinToString("\n") { (timeMs, text) ->
            val totalSeconds = timeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val centiseconds = (timeMs % 1000) / 10
            "[%02d:%02d.%02d]%s".format(minutes, seconds, centiseconds, text)
        }

    private fun extractLyricsText(browseJsonString: String): String? {
        return try {
            val root = json.parseToJsonElement(browseJsonString).jsonObject
            val runs = root["contents"]
                ?.jsonObject?.get("sectionListRenderer")
                ?.jsonObject?.get("contents")
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("musicDescriptionShelfRenderer")
                ?.jsonObject?.get("description")
                ?.jsonObject?.get("runs")
                ?.jsonArray ?: return null

            val text = runs.joinToString("") { element ->
                element.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            }

            text.trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            LogHelper.printd("YTM: could not extract lyrics text — ${e.message}")
            null
        }
    }
}
