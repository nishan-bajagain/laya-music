package ca.ilianokokoro.umihi.music.core.managers

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ca.ilianokokoro.umihi.music.BuildConfig
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.helpers.ConnectivityHelper
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.workers.AppUpdateDownloadWorker
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * App-wide singleton for the self-update flow: checks GitHub Releases for a
 * newer APK, exposes the result as state, enqueues the APK download and
 * triggers the system install screen.
 *
 * Every entry point is gated by [BuildConfig.SELF_UPDATE_ENABLED] so none of
 * this runs in the Play-Store `store` flavor. All failures are silent (logged
 * via [LogHelper.printe]) — a failed update check must never surface an error.
 */
object UpdateManager {

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val notes: String?
    )

    /** Outcome of a check, surfaced to the manual "Check for Updates" row. */
    enum class UpdateCheckResult {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        RATE_LIMITED,
        FAILED
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    /** True while a GitHub check is running — prevents concurrent API calls. */
    private val checkInFlight = AtomicBoolean(false)

    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)

    /** A newer, non-dismissed release that the user has not acted on yet. */
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate.asStateFlow()

    private val _readyToInstallApk = MutableStateFlow<File?>(null)

    /** Set when the APK download finished while the app is foregrounded. */
    val readyToInstallApk: StateFlow<File?> = _readyToInstallApk.asStateFlow()

    /**
     * Check GitHub for a newer release.
     *
     * @param force when true, ignores the [Constants.Update.MIN_RECHECK_INTERVAL_MS]
     *   throttle (used by the manual "Check for Updates" row).
     * @param onResult invoked on the main thread with the check outcome. This
     *   never throws and never surfaces raw errors: unparseable tags, missing
     *   APK assets and 404s are reported as [UpdateCheckResult.UP_TO_DATE].
     */
    fun checkForUpdate(
        context: Context,
        force: Boolean = false,
        onResult: ((UpdateCheckResult) -> Unit)? = null
    ) {
        // Coalesce: if a check is already running, don't fire a second GitHub
        // request — a burst of taps must not be able to burn the API rate limit.
        if (!checkInFlight.compareAndSet(false, true)) {
            scope.launch {
                withContext(Dispatchers.Main) {
                    onResult?.invoke(UpdateCheckResult.UP_TO_DATE)
                }
            }
            return
        }

        scope.launch {
            try {
                val outcome = runUpdateCheck(context, force)
                withContext(Dispatchers.Main) {
                    onResult?.invoke(outcome)
                }
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    private suspend fun runUpdateCheck(context: Context, force: Boolean): UpdateCheckResult {
        return try {
            val repo = DatastoreRepository(context)
            val now = System.currentTimeMillis()
            val lastCheckMs = repo.getLastUpdateCheckMs()

            if (!force) {
                // Auto-check only runs when the user opted in and the throttle
                // interval has elapsed since the last check.
                if (!repo.getSettings().autoCheckForUpdates) {
                    return UpdateCheckResult.UP_TO_DATE
                }
                if (now - lastCheckMs < Constants.Update.MIN_RECHECK_INTERVAL_MS) {
                    return UpdateCheckResult.UP_TO_DATE
                }
            } else if (now - lastCheckMs < Constants.Update.MIN_MANUAL_RECHECK_MS) {
                // Manual check: bypass the 20h throttle, but a check ran very
                // recently (e.g. a double-tap) — reuse the cached result and
                // skip the network call entirely.
                return cachedUpdateResult(repo)
            }

            when (val fetch = fetchLatestRelease()) {
                is ReleaseFetchResult.Success -> {
                    // A check happened — record it so the auto-check doesn't
                    // hammer the API, and remember the response so a later
                    // rate-limited/offline check can still answer.
                    repo.saveLastUpdateCheckMs(now)
                    repo.saveLastUpdateResponse(fetch.rawBody)

                    val info = fetch.info
                    if (!isNewerVersion(info.version, BuildConfig.VERSION_NAME)) {
                        return UpdateCheckResult.UP_TO_DATE
                    }

                    // Never resurface the popup for a version the user skipped, but do
                    // resurface it once an even newer version is published.
                    if (repo.getSettings().dismissedUpdateVersion == info.version) {
                        return UpdateCheckResult.UP_TO_DATE
                    }

                    _availableUpdate.value = info
                    UpdateCheckResult.UPDATE_AVAILABLE
                }

                // No releases, a release without the APK asset, or a tag we
                // can't compare — never an error, just nothing to install.
                ReleaseFetchResult.NoInstallableRelease -> UpdateCheckResult.UP_TO_DATE

                ReleaseFetchResult.RateLimited -> {
                    // GitHub said stop — back off the auto-check and answer from
                    // the last known response instead of surfacing an error.
                    LogHelper.printe("Update check rate limited — using cached result")
                    repo.saveLastUpdateCheckMs(now)
                    cachedUpdateResult(repo)
                }

                ReleaseFetchResult.Failed -> {
                    // Network/offline — degrade to the last known response.
                    LogHelper.printe("Update check failed — using cached result")
                    cachedUpdateResult(repo)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("Update check failed: ${e.message}", exception = e)
            UpdateCheckResult.FAILED
        }
    }

    /**
     * Answers from the last successfully fetched release response. Returns
     * [UpdateCheckResult.UPDATE_AVAILABLE] if that release is still newer than
     * the installed version and wasn't dismissed; otherwise UP_TO_DATE. Never
     * reports an error.
     */
    private suspend fun cachedUpdateResult(repo: DatastoreRepository): UpdateCheckResult {
        val cached = repo.getLastUpdateResponse()?.let { parseRelease(it) }
            ?: return UpdateCheckResult.UP_TO_DATE

        if (!isNewerVersion(cached.version, BuildConfig.VERSION_NAME)) {
            return UpdateCheckResult.UP_TO_DATE
        }
        if (repo.getSettings().dismissedUpdateVersion == cached.version) {
            return UpdateCheckResult.UP_TO_DATE
        }

        _availableUpdate.value = cached
        return UpdateCheckResult.UPDATE_AVAILABLE
    }

    private sealed interface ReleaseFetchResult {
        data class Success(val info: UpdateInfo, val rawBody: String) : ReleaseFetchResult
        data object NoInstallableRelease : ReleaseFetchResult
        data object RateLimited : ReleaseFetchResult
        data object Failed : ReleaseFetchResult
    }

    private suspend fun fetchLatestRelease(): ReleaseFetchResult {
        val request = Request.Builder()
            .url(Constants.Update.GITHUB_LATEST_RELEASE_URL)
            .get()
            .build()

        val result = withTimeoutOrNull(Constants.Update.CHECK_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    UmihiHttpClient.client.newCall(request).execute().use { response ->
                        when {
                            response.code == 404 -> ReleaseFetchResult.NoInstallableRelease
                            response.code == 403 || response.code == 429 ->
                                ReleaseFetchResult.RateLimited

                            !response.isSuccessful -> {
                                LogHelper.printe("Update check HTTP ${response.code}")
                                ReleaseFetchResult.Failed
                            }

                            else -> {
                                val body = response.body?.string()
                                val info = body?.let { parseRelease(it) }
                                if (info == null) {
                                    LogHelper.printe("Update check: no installable release found")
                                    ReleaseFetchResult.NoInstallableRelease
                                } else {
                                    ReleaseFetchResult.Success(info, body)
                                }
                            }
                        }
                    }
                }.getOrElse { e ->
                    LogHelper.printe(
                        "Update check request failed: ${e.message}",
                        exception = e as? Exception
                    )
                    ReleaseFetchResult.Failed
                }
            }
        }

        if (result == null) {
            LogHelper.printe("Update check timed out after ${Constants.Update.CHECK_TIMEOUT_MS}ms")
            return ReleaseFetchResult.Failed
        }
        return result
    }

    internal fun parseRelease(body: String): UpdateInfo? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
                ?: return null
            val notes = root["body"]?.jsonPrimitive?.contentOrNull
            val downloadUrl = root["assets"]
                ?.jsonArray
                ?.firstOrNull { asset ->
                    asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull ==
                        Constants.Update.APK_ASSET_NAME
                }
                ?.jsonObject
                ?.get("browser_download_url")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: return null

            UpdateInfo(version = tag, downloadUrl = downloadUrl, notes = notes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LogHelper.printe("Failed to parse update response: ${e.message}", exception = e)
            null
        }
    }

    /**
     * Compares two version strings component-by-component (numerically,
     * left-to-right). A leading `v`/`V` is ignored and missing trailing
     * components count as `0`. Non-numeric input makes the result `false` —
     * we can't prove the remote is newer, so we don't prompt.
     */
    internal fun isNewerVersion(remoteTag: String, localVersionName: String): Boolean {
        val remote = parseVersion(remoteTag) ?: return false
        val local = parseVersion(localVersionName) ?: return false
        val maxComponents = maxOf(remote.size, local.size)
        for (i in 0 until maxComponents) {
            val remoteComponent = remote.getOrElse(i) { 0 }
            val localComponent = local.getOrElse(i) { 0 }
            if (remoteComponent != localComponent) {
                return remoteComponent > localComponent
            }
        }
        return false
    }

    internal fun parseVersion(raw: String): List<Int>? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null

        // Ignore semver pre-release ("-rc1", "-beta") and build-metadata
        // ("+5") suffixes for the numeric comparison: "v1.0.4-rc1" compares
        // as "1.0.4", so a pre-release never beats the stable release of the
        // same version but is still newer than an older stable one.
        val core = trimmed.substringBefore('+').substringBefore('-')
        if (core.isEmpty()) return null

        val components = core.split('.')
        if (components.any { it.isEmpty() }) return null

        return components.map { it.toIntOrNull() ?: return null }
    }

    /** Enqueues the APK download as a unique WorkManager job ("Update now"). */
    fun startUpdateDownload(context: Context) {
        val info = _availableUpdate.value ?: return
        val appContext = context.applicationContext

        scope.launch {
            try {
                // Respect the metered-network setting the same way song/playlist
                // downloads do: only allow mobile-data downloads when enabled.
                val useMetered = DatastoreRepository(appContext).getSettings().downloadOnMetered

                val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
                    .setInputData(
                        workDataOf(
                            AppUpdateDownloadWorker.VERSION_KEY to info.version,
                            AppUpdateDownloadWorker.URL_KEY to info.downloadUrl
                        )
                    )
                    .setConstraints(
                        Constraints(
                            requiredNetworkType = if (useMetered) {
                                NetworkType.CONNECTED
                            } else {
                                NetworkType.UNMETERED
                            },
                            requiresStorageNotLow = true
                        )
                    )
                    .build()

                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    Constants.Update.DOWNLOAD_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )

                if (!useMetered && ConnectivityHelper.isMeteredNetwork(appContext)) {
                    NotificationManager.showUpdateWaitingForWifi(appContext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogHelper.printe(
                    "Failed to enqueue update download: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /** Called by [AppUpdateDownloadWorker] once the APK is on disk. */
    fun onUpdateDownloaded(context: Context, apkFile: File, version: String) {
        _readyToInstallApk.value = apkFile
        NotificationManager.showUpdateReadyToInstall(context, apkFile, version)
    }

    /** The single place that builds the install Intent (reused by the notification). */
    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun installUpdate(context: Context, apkFile: File) {
        // API 26+ requires the "install unknown apps" runtime permission; below
        // that the install intent works without it, so skip the check.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            Toast.makeText(
                context,
                context.getString(R.string.update_install_permission_needed),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        context.startActivity(buildInstallIntent(context, apkFile))
    }

    /** "Later" — close the popup; the next check may ask again. */
    fun dismissUpdatePrompt() {
        _availableUpdate.value = null
    }

    /** Close the "ready to install" prompt (the notification remains available). */
    fun dismissReadyToInstall() {
        _readyToInstallApk.value = null
    }
}
