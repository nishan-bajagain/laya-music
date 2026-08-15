package ca.ilianokokoro.umihi.music.core

import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Captures the latest image-fetch failure (URL + HTTP status or exception) so
 * it can be shown on-screen in debug builds and logged to Logcat under the
 * "UmihiPrint" tag. Every image request in the app funnels through
 * [UmihiHttpClient.imageClient], so this is a single point of truth for why
 * posters/artwork fail to load.
 */
object ImageErrorLog {
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun record(message: String) {
        _lastError.value = message
        LogHelper.printe("Image fetch failed: $message")
    }

    /**
     * Clears the last error once an image actually loads. The candidate-cycling
     * flow (local file → API art → i.ytimg fallback) intentionally tries URLs
     * that may fail — a 403 on candidate one is expected and harmless when the
     * fallback succeeds. Clearing on success keeps the on-screen debug banner
     * truthful: it only shows while posters are genuinely failing to render.
     */
    fun clear() {
        _lastError.value = null
    }
}

object UmihiHttpClient {

    /**
     * Browser identity for artwork fetches. Google's image CDNs
     * (i.ytimg.com, lh3.googleusercontent.com, yt3.ggpht.com) reject requests
     * carrying OkHttp's default `okhttp/x.y.z` User-Agent with HTTP 403, which
     * is why every poster in the app was blank while the YouTube API calls
     * (which send browser headers via YoutubeAuthHelper) succeeded. Sending a
     * browser UA on image requests only is harmless and unblocks the CDNs.
     */
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Client for fetching artwork/images (Coil image loader, notification
     * artwork, offline thumbnail downloads). Same timeouts and shared
     * connection pool as [client], plus browser identity headers so Google's
     * image CDNs don't 403 the requests, and a failure interceptor that
     * records the exact reason (HTTP status or exception) into
     * [ImageErrorLog]. Everything else keeps using [client] untouched.
     */
    val imageClient: OkHttpClient by lazy {
        client.newBuilder()
            .addInterceptor { chain -> // browser identity
                val request = chain.request()
                val builder = request.newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                // The music.youtube.com Referer/Origin are only attached to
                // YouTube's own thumbnail CDNs (i.ytimg.com, yt3.ggpht.com),
                // where they were added to stop 403s. Google's account-photo
                // CDN (lh3.googleusercontent.com) rejects some requests
                // carrying that cross-site Origin/Referer with a 403/400 — the
                // profile avatar was the victim because, unlike song artwork,
                // it has no i.ytimg.com fallback. The avatar must go out
                // without those headers; the browser UA (harmless everywhere)
                // stays on for every host.
                if (isYouTubeImageCdn(request.url.host)) {
                    builder.header("Referer", "https://music.youtube.com/")
                    builder.header("Origin", "https://music.youtube.com")
                }
                chain.proceed(builder.build())
            }
            .addInterceptor { chain -> // failure capture
                val request = chain.request()
                try {
                    val response = chain.proceed(request)
                    if (!response.isSuccessful) {
                        ImageErrorLog.record(
                            "HTTP ${response.code} ${response.message} for ${request.url}"
                        )
                    }
                    response
                } catch (e: Exception) {
                    ImageErrorLog.record(
                        "${e.javaClass.simpleName}: ${e.message ?: "no message"} for ${request.url}"
                    )
                    throw e
                }
            }
            .build()
    }

    val downloadClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Whether an image request to [host] should carry the music.youtube.com
 * Referer/Origin headers. True only for YouTube's own image CDNs
 * (i.ytimg.com, yt3.ggpht.com and their siblings); false for Google's
 * account-photo CDN (lh3.googleusercontent.com) and every other host, so the
 * profile avatar — which has no i.ytimg.com fallback — is never sent with the
 * cross-site Origin/Referer that can get it 403'd. Extracted as a pure
 * function so the header policy is unit-testable.
 */
internal fun isYouTubeImageCdn(host: String): Boolean =
    host.endsWith(".ytimg.com") || host.endsWith(".ggpht.com")