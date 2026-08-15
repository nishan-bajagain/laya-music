package ca.ilianokokoro.umihi.music.core.helpers

import android.content.Context
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printd
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Single source of truth for parsing the YouTube Music account_menu response
 * and keeping the account profile (name, email, avatar) populated.
 *
 * [fetchAndSaveIfMissing] is safe to call on app start and on every profile
 * screen open: it returns immediately when the data is already complete, and
 * failures are logged, never thrown. This is what makes a blank avatar
 * self-heal — a failed/empty fetch at login time gets retried on the next
 * launch instead of staying empty forever.
 */
object AccountInfoHelper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Fetches and persists account info if any of name / email / avatar is
     * missing. No-op when logged out or when all fields are already present.
     */
    suspend fun fetchAndSaveIfMissing(context: Context) {
        val datastoreRepository = DatastoreRepository(context)
        try {
            val settings = datastoreRepository.getSettings()
            if (settings.cookies.isEmpty()) return

            val name = datastoreRepository.accountName.first()
            val email = datastoreRepository.accountEmail.first()
            val avatarUrl = datastoreRepository.accountAvatarUrl.first()
            if (name.isNotBlank() && email.isNotBlank() && avatarUrl.isNotBlank()) return

            val responseJson = YoutubeApiClient.getAccountMenu(settings)
            val (parsedName, parsedEmail, parsedAvatar) = parseAccountInfo(responseJson)
            if (parsedName.isNotBlank() || parsedEmail.isNotBlank() || parsedAvatar.isNotBlank()) {
                datastoreRepository.saveAccountInfo(parsedName, parsedEmail, parsedAvatar)
            }
        } catch (e: Exception) {
            printe(
                "AccountInfoHelper: failed to refresh account info: ${e.message}",
                exception = e
            )
        }
    }

    /**
     * Parses the account_menu API response. Returns Triple(name, email,
     * avatarUrl) — blank fields on parse failure. The avatar URL is normalized
     * (sanitized + size-upgraded) before being returned.
     */
    fun parseAccountInfo(jsonString: String): Triple<String, String, String> {
        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject

            // Both paths are tried even when "actions" is absent — the old
            // code returned early on a missing actions array, which made the
            // root-level header fallback unreachable.
            val headerRenderer = findActiveAccountHeader(root)
                ?: return Triple("", "", "")

            val name = headerRenderer["accountName"].textFromRuns() ?: ""
            val email = headerRenderer["email"].textFromRuns() ?: ""

            val avatarUrl = extractAvatarUrl(headerRenderer)
            if (avatarUrl.isBlank()) {
                // Debug-only diagnostics for avatar-schema drift. The scraped
                // account_menu response has changed shape more than once, and a
                // blank avatar means the accountPhoto sub-object no longer
                // matches any known shape — printing it (never the full
                // response) makes the next debug run show exactly what Google
                // is sending. Gated on BuildConfig.DEBUG, so it is dead code in
                // release builds and can never reach crash reports.
                printd(
                    "AccountInfoHelper: avatar URL not found; " +
                        "accountPhoto=${headerRenderer["accountPhoto"]}"
                )
            }

            Triple(name, email, avatarUrl)
        } catch (e: Exception) {
            printe("AccountInfoHelper: could not parse account info: ${e.message}", exception = e)
            Triple("", "", "")
        }
    }

    /**
     * Extracts the largest available account-photo URL from the
     * activeAccountHeaderRenderer.
     *
     * YouTube's (undocumented, scraped) account_menu response has carried the
     * photo under several shapes over time, so every recognized one is
     * collected and the largest by pixel area is returned — dimension-less
     * URLs act as ordered fallbacks. Recognized shapes:
     *
     *  - `accountPhoto.thumbnails[]` (classic)
     *  - `accountPhoto.thumbnail.thumbnails[]` (newer wrapper)
     *  - `accountPhoto.image.sources[]` / `accountPhoto.avatar.sources[]`
     *    (newest "image renderer" pattern)
     *  - `accountPhoto.url` / `accountPhoto.thumbnail.url` (direct)
     *  - `accountPhoto` as a plain string URL
     *  - renamed photo keys (`accountAvatar`, `avatar`, `image`, `photo`)
     *
     * The largest thumbnail is chosen by comparing width/height values when
     * present, never by assuming array order — YouTube has ordered these both
     * smallest→largest and largest→smallest at different times. Never throws:
     * an unrecognized shape yields "" while name/email still parse.
     */
    private fun extractAvatarUrl(headerRenderer: JsonObject): String {
        val withSize = mutableListOf<Pair<String, Int>>()
        val withoutSize = mutableListOf<String>()

        fun consider(url: String?, width: Int?, height: Int?) {
            val normalized = UmihiHelper.normalizeGoogleAvatarUrl(url)
            if (normalized.isBlank()) return
            val area = when {
                width != null && height != null -> width * height
                width != null -> width
                height != null -> height
                else -> -1
            }
            if (area > 0) withSize += normalized to area else withoutSize += normalized
        }

        fun considerThumbnail(entry: JsonElement?) {
            val obj = entry?.asObject() ?: return
            consider(
                obj["url"]?.asString(),
                obj["width"]?.asInt(),
                obj["height"]?.asInt()
            )
            // Some responses wrap the entry itself: {"thumbnail": {"url": …}}.
            val nested = obj["thumbnail"]?.asObject()
            if (nested != null) {
                consider(
                    nested["url"]?.asString(),
                    nested["width"]?.asInt(),
                    nested["height"]?.asInt()
                )
            }
        }

        fun considerArray(array: JsonElement?) {
            array?.asArray()?.forEach { considerThumbnail(it) }
        }

        // Keys that have carried the photo across schema revisions, in order of
        // likelihood. All are collected so the largest photo wins regardless of
        // which key held it.
        val photoKeys = listOf("accountPhoto", "accountAvatar", "avatar", "image", "photo")
        val arrayKeys = listOf("thumbnails", "sources")
        val wrapperKeys = listOf("thumbnail", "image", "avatar", "photo")

        for (key in photoKeys) {
            val photo = headerRenderer[key] ?: continue

            // Plain string URL.
            val directString = photo.asString()
            if (directString != null) {
                consider(directString, null, null)
                continue
            }

            val photoObject = photo.asObject() ?: continue

            // Direct URL on the photo object.
            consider(
                photoObject["url"]?.asString(),
                photoObject["width"]?.asInt(),
                photoObject["height"]?.asInt()
            )

            // Direct arrays: photo.thumbnails[] / photo.sources[].
            arrayKeys.forEach { considerArray(photoObject[it]) }

            // Wrapped arrays/URLs: photo.thumbnail.thumbnails[],
            // photo.image.sources[], photo.avatar.url, etc.
            for (wrapperKey in wrapperKeys) {
                val wrapper = photoObject[wrapperKey]?.asObject() ?: continue
                consider(
                    wrapper["url"]?.asString(),
                    wrapper["width"]?.asInt(),
                    wrapper["height"]?.asInt()
                )
                arrayKeys.forEach { considerArray(wrapper[it]) }
            }
        }

        return if (withSize.isNotEmpty()) {
            withSize.maxByOrNull { it.second }?.first ?: ""
        } else {
            withoutSize.firstOrNull() ?: ""
        }
    }

    /**
     * Locates the activeAccountHeaderRenderer via the primary
     * `actions[].openPopupAction.popup.multiPageMenuRenderer.header` path, then
     * falls back to the root-level `header` used by some API versions. All-safe
     * casts: a malformed or missing branch degrades to null instead of
     * aborting the parse.
     */
    private fun findActiveAccountHeader(root: JsonObject): JsonObject? {
        root["actions"]
            .asArray()
            ?.firstOrNull()
            ?.asObject()
            ?.get("openPopupAction")
            ?.asObject()
            ?.get("popup")
            ?.asObject()
            ?.get("multiPageMenuRenderer")
            ?.asObject()
            ?.get("header")
            ?.asObject()
            ?.get("activeAccountHeaderRenderer")
            ?.asObject()
            ?.let { return it }

        return root["header"]
            ?.asObject()
            ?.get("activeAccountHeaderRenderer")
            ?.asObject()
    }

    /**
     * Reads a "runs" style field (`{"runs": [{"text": "…"}]}`), falling back to
     * a plain string. Uses safe casts so a malformed field degrades to null
     * instead of aborting the whole parse.
     */
    private fun JsonElement?.textFromRuns(): String? {
        val obj = this?.asObject() ?: return this?.asString()
        val runs = obj["runs"]?.asArray() ?: return null
        return runs.firstOrNull()?.asObject()?.get("text")?.asString()
    }

    // Safe casts — kotlinx's jsonObject/jsonArray extensions throw when the
    // element has a different type; these return null so schema drift can't
    // take down the whole parse.
    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.asInt(): Int? = (this as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
