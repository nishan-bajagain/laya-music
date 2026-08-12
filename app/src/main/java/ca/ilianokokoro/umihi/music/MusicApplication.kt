package ca.ilianokokoro.umihi.music

import android.app.Application
import ca.ilianokokoro.umihi.music.core.UmihiHttpClient
import ca.ilianokokoro.umihi.music.core.YoutubeExtractor
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.imageDecoderEnabled
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.Logger
import org.schabi.newpipe.extractor.NewPipe

class MusicApplication : Application(), SingletonImageLoader.Factory {

    /**
     * Global Coil loader used by every AsyncImage in the app. Capping the
     * memory cache at 20% of device RAM and the disk cache at 2% keeps
     * scrolling lists from evicting everything else (and from blowing past
     * the heap on low-RAM devices) while still caching art across screens.
     *
     * The network fetcher is wired explicitly instead of relying on
     * ServiceLoader auto-registration from coil-network-okhttp: image
     * requests are guaranteed to go through the app's own shared
     * [UmihiHttpClient] (one connection pool and one set of timeouts
     * app-wide instead of Coil building its own bare OkHttpClient), and a
     * silently missing fetcher can't produce a blank-everywhere screen.
     * A DebugLogger is attached in debug builds so any fetch failure
     * (403, DNS, TLS, "no fetcher found", …) shows up in Logcat under the
     * "Coil" tag instead of failing silently.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { UmihiHttpClient.imageClient }))
            }
            // ImageDecoder is disabled: several OEM devices (Realme/Oppo and
            // friends are the usual culprits — this app's crash reports come from
            // a Realme RMX3933 on Android 16) fail to decode images with the
            // ImageDecoder, surfacing as posters that "never load" even though
            // the bytes downloaded fine. This forces Coil onto the classic,
            // battle-tested BitmapFactory path that works everywhere; thumbnails
            // here are tiny (48–150dp) so there is no measurable quality cost.
            .imageDecoderEnabled(false)
            .apply {
                if (BuildConfig.DEBUG) {
                    // Errors only, never the per-request success lifecycle lines:
                    // fast-scrolling a long playlist fires dozens of requests and
                    // DebugLogger's line-per-request logging becomes real logd I/O
                    // on mid-range devices. minLevel = Error keeps every failure
                    // (the diagnostic we need) while dropping the success noise.
                    logger(ErrorOnlyLogger())
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .maxSizePercent(0.02)
                    // 2% of a device with heavy other-app storage use can be only
                    // a few MB — small enough that thumbnails get evicted
                    // constantly, forcing re-fetches. Never let the cap fall
                    // below 100MB so cached art survives longer.
                    .minimumMaxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.YTM_API_KEY.isBlank()) {
            LogHelper.printe(
                "YTM_API_KEY is blank — all YouTube Music API calls will fail"
            )
        }
        NotificationManager.init(this)
        // Initialize NewPipe here so it is ready before any service or
        // background task attempts to resolve a YouTube stream URL.
        NewPipe.init(YoutubeExtractor())
    }
}

/**
 * Coil logger that only surfaces errors. See the debug-only logger wiring in
 * [MusicApplication.newImageLoader] — the per-request lifecycle lines are pure
 * overhead during fast list scrolling.
 */
private class ErrorOnlyLogger : Logger {
    override var minLevel: Logger.Level = Logger.Level.Error

    override fun log(tag: String, level: Logger.Level, message: String?, throwable: Throwable?) {
        LogHelper.printe("[$tag] ${message ?: ""}", exception = throwable as? Exception)
    }
}
