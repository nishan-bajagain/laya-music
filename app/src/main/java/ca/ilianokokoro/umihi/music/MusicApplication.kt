package ca.ilianokokoro.umihi.music

import android.app.Application
import ca.ilianokokoro.umihi.music.core.YoutubeExtractor
import ca.ilianokokoro.umihi.music.core.managers.NotificationManager
import org.schabi.newpipe.extractor.NewPipe

class MusicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationManager.init(this)
        // Initialize NewPipe here so it is ready before any service or
        // background task attempts to resolve a YouTube stream URL.
        NewPipe.init(YoutubeExtractor())
    }
}
