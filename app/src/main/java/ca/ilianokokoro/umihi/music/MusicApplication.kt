package ca.ilianokokoro.umihi.music

import android.app.Application
import ca.ilianokokoro.umihi.music.core.YoutubeExtractor
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe

class MusicApplication : Application(), SingletonImageLoader.Factory {

    /**
     * Global Coil loader used by every AsyncImage in the app. Capping the
     * memory cache at 20% of device RAM and the disk cache at 2% keeps
     * scrolling lists from evicting everything else (and from blowing past
     * the heap on low-RAM devices) while still caching art across screens.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
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
