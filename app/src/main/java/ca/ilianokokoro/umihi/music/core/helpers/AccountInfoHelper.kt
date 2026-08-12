package ca.ilianokokoro.umihi.music.core.helpers

import android.content.Context
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeApiClient
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            val actions = root["actions"]?.jsonArray ?: return Triple("", "", "")

            var headerRenderer = actions.firstOrNull()
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

            val avatarUrl = UmihiHelper.normalizeGoogleAvatarUrl(
                headerRenderer["accountPhoto"]
                    ?.jsonObject?.get("thumbnails")
                    ?.jsonArray?.lastOrNull()
                    ?.jsonObject?.get("url")
                    ?.jsonPrimitive?.contentOrNull
            )

            Triple(name, email, avatarUrl)
        } catch (e: Exception) {
            printe("AccountInfoHelper: could not parse account info: ${e.message}", exception = e)
            Triple("", "", "")
        }
    }
}
