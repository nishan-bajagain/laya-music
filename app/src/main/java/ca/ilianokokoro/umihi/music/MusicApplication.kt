package ca.ilianokokoro.umihi.music

import android.app.Application
import ca.ilianokokoro.umihi.music.core.YoutubeExtractor
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import org.schabi.newpipe.extractor.NewPipe

class MusicApplication : Application() {

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
