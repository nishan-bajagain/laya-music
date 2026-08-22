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
            printd("AccountInfoHelper fetchAndSave: name=${parsedName.take(30)} email=${parsedEmail.take(30)} avatar=${parsedAvatar.take(120)}")
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

            val headerRenderer = findActiveAccountHeader(root)
            if (headerRenderer == null) {
                printd(
                    "AccountInfoHelper: activeAccountHeaderRenderer not found; " +
                        "rootKeys=${root.keys}"
                )
                return Triple("", "", "")
            }

            val name = headerRenderer["accountName"].textFromRuns() ?: ""
            val email = headerRenderer["email"].textFromRuns() ?: ""

            val avatarUrl = extractAvatarUrl(headerRenderer, root)
            if (avatarUrl.isBlank()) {
                // Dump the header structure for diagnosis — keys + accountPhoto
                // value reveals the new schema without dumping PII.
                printd(
                    "AccountInfoHelper: avatar URL not found; " +
                        "headerKeys=${headerRenderer.keys} " +
                        "accountPhoto=${headerRenderer["accountPhoto"]}"
                )
                // Also dump ALL keys in the entire root for a complete picture
                printd(
                    "AccountInfoHelper: rootKeys=${root.keys} rootSize=${root.size}"
                )
            } else {
                printd("AccountInfoHelper: avatar extracted: ${avatarUrl.take(120)}")
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
     * URLs act as ordered fallbacks.
     *
     * The largest thumbnail is chosen by comparing width/height values when
     * present, never by assuming array order. Never throws: an unrecognized
     * shape yields "" while name/email still parse.
     */
    private fun extractAvatarUrl(headerRenderer: JsonObject, root: JsonObject): String {
        val candidates = mutableListOf<Pair<String, Int>>()
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
            if (area > 0) candidates += normalized to area else withoutSize += normalized
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

        /**
         * Recursively search a JSON tree for any image URL. Handles both
         * objects and arrays at every nesting level. This is the nuclear
         * fallback for when YouTube adds new nesting levels that the
         * explicit pattern matching above doesn't cover.
         */
        fun considerDeep(element: JsonElement?, depth: Int = 0) {
            if (element == null || depth > 10) return
            when (element) {
                is JsonObject -> {
                    for (urlKey in listOf("url", "imageUrl", "src", "photoUrl", "uri")) {
                        val url = element[urlKey]?.asString()
                        if (url != null && UmihiHelper.sanitizeImageUrl(url).isNotBlank()) {
                            consider(url, element["width"]?.asInt(), element["height"]?.asInt())
                        }
                    }
                    for (arrayKey in listOf("thumbnails", "sources", "contents")) {
                        val arr = element[arrayKey]?.asArray()
                        if (arr != null) {
                            arr.forEach { considerThumbnail(it) }
                        }
                    }
                    for ((_, value) in element) {
                        considerDeep(value, depth + 1)
                    }
                }
                is JsonArray -> {
                    element.forEach { considerDeep(it, depth + 1) }
                }
                else -> {}
            }
        }

        // Keys that have carried the photo across schema revisions, in order of
        // likelihood. All are collected so the largest photo wins regardless of
        // which key held it.
        val photoKeys = listOf(
            "accountPhoto", "accountAvatar", "avatar", "image", "photo",
            "profilePicture", "profilePhoto", "avatarImage"
        )
        val arrayKeys = listOf("thumbnails", "sources")
        val wrapperKeys = listOf(
            "thumbnail", "image", "avatar", "photo",
            "photoRenderer", "customThumbnail", "customPhoto",
            "imageRenderer", "avatarRenderer"
        )

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

                // One more level of nesting for deeply wrapped patterns
                for (innerKey in wrapperKeys) {
                    val inner = wrapper[innerKey]?.asObject() ?: continue
                    consider(
                        inner["url"]?.asString(),
                        inner["width"]?.asInt(),
                        inner["height"]?.asInt()
                    )
                    arrayKeys.forEach { considerArray(inner[it]) }
                }
            }
        }

        // If none of the known keys produced a URL, fall back to deep
        // recursive scans.
        if (candidates.isEmpty() && withoutSize.isEmpty()) {
            printd("AccountInfoHelper: no avatar from known keys, trying deep scan of header")
            considerDeep(headerRenderer)
        }

        if (candidates.isEmpty() && withoutSize.isEmpty()) {
            printd("AccountInfoHelper: deep scan of header empty, scanning full root")
            considerDeep(root)
        }

        // Last resort: scan for any Google/YouTube image CDN URL in the entire tree.
        // This handles completely new API shapes by matching known CDN hostnames.
        if (candidates.isEmpty() && withoutSize.isEmpty()) {
            printd("AccountInfoHelper: scanning root for image CDN URLs")
            findImageCdnUrls(root).forEach { consider(it, null, null) }
        }

        // Nuclear option: recursively search the entire accountPhoto subtree
        // (and all header subtrees) for any thumbnail array or URL, regardless
        // of key names. YouTube's API schema drift is the primary cause of
        // blank avatars.
        if (candidates.isEmpty() && withoutSize.isEmpty()) {
            printd("AccountInfoHelper: all extraction failed, trying subtree scan")
            val photo = headerRenderer["accountPhoto"]
            val found = findThumbnailInSubtree(photo)
            if (found.isNotBlank()) {
                consider(found, null, null)
            } else {
                // Try every top-level key in the header as a potential photo container
                for ((_, v) in headerRenderer) {
                    val found2 = findThumbnailInSubtree(v)
                    if (found2.isNotBlank()) {
                        consider(found2, null, null)
                        break
                    }
                }
            }
        }

        val result = if (candidates.isNotEmpty()) {
            candidates.maxByOrNull { it.second }?.first ?: ""
        } else {
            withoutSize.firstOrNull() ?: ""
        }

        printd("AccountInfoHelper extractAvatar: candidates=${candidates.size} noSize=${withoutSize.size} result=${result.take(100)}")
        return result
    }

    /**
     * Scans the entire JSON tree for any URL that looks like it comes from a
     * known Google/YouTube image CDN. This is the final fallback for completely
     * new API shapes.
     */
    private fun findImageCdnUrls(element: JsonElement?, depth: Int = 0): List<String> {
        if (element == null || depth > 10) return emptyList()
        val results = mutableListOf<String>()
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    if (value is JsonPrimitive) {
                        val url = value.contentOrNull
                        if (url != null && looksLikeImageCdnUrl(url)) {
                            results.add(url)
                        }
                    } else {
                        results.addAll(findImageCdnUrls(value, depth + 1))
                    }
                }
            }
            is JsonArray -> {
                element.forEach { results.addAll(findImageCdnUrls(it, depth + 1)) }
            }
            else -> {}
        }
        return results
    }

    /** Returns true if the string looks like a Google/YouTube image CDN URL. */
    private fun looksLikeImageCdnUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val lower = url.lowercase()
        return lower.contains("ggpht.com") ||
            lower.contains("googleusercontent.com") ||
            lower.contains("ytimg.com") ||
            lower.contains("google.com/images")
    }

    /**
     * Looks for a thumbnail URL inside an arbitrary JSON subtree that might
     * be an accountPhoto-like object, regardless of its exact key names or
     * nesting depth. YouTube periodically restructures this object, so we
     * search for any URL field at any depth and also for any "thumbnails"
     * array at any depth.
     */
    private fun findThumbnailInSubtree(element: JsonElement?, depth: Int = 0): String {
        if (element == null || depth > 6) return ""
        when (element) {
            is JsonObject -> {
                // Check for a direct "url" or "imageUrl" field
                for (key in listOf("url", "imageUrl", "src", "photoUrl")) {
                    val url = element[key]?.asString()
                    if (url != null) {
                        val sanitized = UmihiHelper.sanitizeImageUrl(url)
                        if (sanitized.isNotBlank()) return sanitized
                    }
                }
                // Check for a thumbnails array
                val thumbs = element["thumbnails"]?.asArray()
                if (thumbs != null) {
                    var best = ""
                    var bestArea = 0
                    for (t in thumbs) {
                        val obj = t.asObject() ?: continue
                        val url = obj["url"]?.asString() ?: continue
                        val sanitized = UmihiHelper.sanitizeImageUrl(url)
                        if (sanitized.isBlank()) continue
                        val w = obj["width"]?.asInt() ?: 0
                        val h = obj["height"]?.asInt() ?: 0
                        val area = w * h
                        if (area > bestArea) { bestArea = area; best = sanitized }
                    }
                    if (best.isNotBlank()) return best
                }
                // Recurse into all values
                for ((_, v) in element) {
                    val found = findThumbnailInSubtree(v, depth + 1)
                    if (found.isNotBlank()) return found
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val found = findThumbnailInSubtree(item, depth + 1)
                    if (found.isNotBlank()) return found
                }
            }
            else -> {}
        }
        return ""
    }

    /**
     * Locates the activeAccountHeaderRenderer via the primary
     * `actions[].openPopupAction.popup.multiPageMenuRenderer.header` path, then
     * falls back to the root-level `header` used by some API versions. All-safe
     * casts: a malformed or missing branch degrades to null instead of
     * aborting the parse.
     */
    private fun findActiveAccountHeader(root: JsonObject): JsonObject? {
        // Path 1: actions[0].openPopupAction.popup.multiPageMenuRenderer.header
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

        // Path 2: root.header.activeAccountHeaderRenderer
        root["header"]
            ?.asObject()
            ?.get("activeAccountHeaderRenderer")
            ?.asObject()
            ?.let { return it }

        // Path 3: root.activeAccountHeaderRenderer (flat)
        root["activeAccountHeaderRenderer"]
            ?.asObject()
            ?.let { return it }

        // Path 4: actions[0].openPopupAction.popup contains activeAccountHeaderRenderer directly
        root["actions"]
            .asArray()
            ?.firstOrNull()
            ?.asObject()
            ?.get("openPopupAction")
            ?.asObject()
            ?.get("popup")
            ?.asObject()
            ?.let { popup ->
                val header = popup["activeAccountHeaderRenderer"]?.asObject()
                if (header != null) return header
                // Some API versions wrap in a renderers array
                val renderers = popup["renderers"]?.asArray()
                renderers?.forEach { renderer ->
                    val obj = renderer.asObject() ?: return@forEach
                    val h = obj["activeAccountHeaderRenderer"]?.asObject()
                    if (h != null) return h
                }
            }

        // Path 5: Deep search — look for activeAccountHeaderRenderer anywhere in the tree
        return findDeep(root, "activeAccountHeaderRenderer", maxDepth = 6)
    }

    /**
     * Recursively search for a key matching [targetKey] anywhere in the JSON tree.
     * Returns the JsonObject value if found, null otherwise.
     */
    private fun findDeep(obj: JsonObject, targetKey: String, maxDepth: Int, depth: Int = 0): JsonObject? {
        if (depth > maxDepth) return null
        for ((key, value) in obj) {
            if (key == targetKey && value is JsonObject) return value
            val childObj = value.asObject() ?: continue
            val found = findDeep(childObj, targetKey, maxDepth, depth + 1)
            if (found != null) return found
        }
        return null
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
