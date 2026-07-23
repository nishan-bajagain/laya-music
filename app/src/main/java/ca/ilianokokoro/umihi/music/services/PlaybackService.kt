package ca.ilianokokoro.umihi.music.services

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.ApiResult
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.ExoCache
import ca.ilianokokoro.umihi.music.core.datasources.YoutubeDataSourceFactory
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.youtube.YoutubeStatsTracker
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.PlaylistRepository
import ca.ilianokokoro.umihi.music.data.repositories.SongRepository
import ca.ilianokokoro.umihi.music.extensions.cappedTo
import ca.ilianokokoro.umihi.music.models.PlaybackAudioInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.uuid.Uuid

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var exoCache: ExoCache
    private lateinit var player: ExoPlayer
    private lateinit var datastoreRepository: DatastoreRepository
    private var currentAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    private val songRepository = SongRepository()
    private lateinit var playlistRepository: PlaylistRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var callback: UmihiMediaLibraryCallback

    /**
     * Set to true the first time an offline playback error triggers a toast this session.
     * Reset to false when the device reconnects so the notice can appear again next time the
     * user goes offline.
     */
    private var offlineNoticeShown = false

    /** Watches for connectivity changes so [offlineNoticeShown] resets on reconnect. */
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null


    override fun onCreate() {
        super.onCreate()

        datastoreRepository = DatastoreRepository(applicationContext)
        playlistRepository = PlaylistRepository(application)
        exoCache = ExoCache(application)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Util.getUserAgent(this, packageName))

        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(exoCache.cache)
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val resolvingFactory = YoutubeDataSourceFactory(application, cacheDataSourceFactory)

        val audioOffloadPreferences =
            TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                .setIsGaplessSupportRequired(true)
                .setIsSpeedChangeSupportRequired(true)
                .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .setDeviceVolumeControlEnabled(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .build()
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?
                ) {
                    PlayerManager.updatePlaybackInfo(
                        PlaybackAudioInfo(
                            format = Constants.ExoPlayer.AUDIO_MIME_MAP[format.sampleMimeType]
                                ?: format.sampleMimeType,
                            sampleRate = format.sampleRate
                                .takeIf { it != Format.NO_VALUE },
                            bitrate = format.bitrate
                                .takeIf { it != Format.NO_VALUE },
                            channelCount = format.channelCount
                                .takeIf { it != Format.NO_VALUE }
                        )
                    )
                }
            }
        )

        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(audioOffloadPreferences)
                .build()

        serviceScope.launch {
            val settings = datastoreRepository.settings.first()
            val mode = if (settings.useAudioOffload) {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            } else {
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
            }
            withContext(Dispatchers.Main) {
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setAudioOffloadPreferences(
                            player.trackSelectionParameters.audioOffloadPreferences
                                .buildUpon()
                                .setAudioOffloadMode(mode)
                                .build()
                        )
                        .build()
            }
        }

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int
            ) {
                PlayerManager.updatePlaybackInfo(PlaybackAudioInfo())
                updateCurrentMediaItemThumbnail(mediaItem)
                val songId = mediaItem?.mediaId ?: return
                serviceScope.launch {
                    val settings = datastoreRepository.getSettings()
                    YoutubeStatsTracker.onPlaybackStarted(songId, settings)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_IDLE) {
                    YoutubeStatsTracker.stopPlaybackTracking()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val online = isNetworkAvailable()
                if (!online) {
                    // Offline — show a human-readable notice exactly once per offline session.
                    // Downloaded songs are served from cache and never trigger this listener;
                    // only non-downloaded songs fail here. We pause rather than skip so the
                    // user is not looped through every undownloaded song in the queue.
                    if (!offlineNoticeShown) {
                        offlineNoticeShown = true
                        Toast.makeText(
                            applicationContext,
                            getString(R.string.no_internet_playback),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    player.pause()
                } else {
                    // Online error (bad stream URL, expired link, etc.) — show the raw message
                    // and skip to the next item as before.
                    Toast.makeText(applicationContext, error.message, Toast.LENGTH_LONG).show()
                    player.seekToNext()
                    player.prepare()
                }
            }

            // Expose audio session ID for third-party equalizer apps
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (currentAudioSessionId == audioSessionId) {
                    return
                }

                if (currentAudioSessionId > 0) {
                    sendBroadcast(
                        Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, currentAudioSessionId)
                            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                        }
                    )
                }

                currentAudioSessionId = audioSessionId

                if (audioSessionId > 0) {
                    sendBroadcast(
                        Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                        }
                    )
                }
            }
        })

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        registerConnectivityObserver()

        callback = UmihiMediaLibraryCallback(
            service = this,
            serviceScope = serviceScope,
            datastoreRepository = datastoreRepository,
            songRepository = songRepository,
            playlistRepository = playlistRepository
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader.Builder(this).build()))
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? = mediaLibrarySession

    /**
     * Called when the user swipes the app away from the Recents (Overview) screen.
     *
     * Previous implementation only stopped the service when the queue was empty,
     * meaning music kept playing after a swipe-away. The fix: always stop cleanly.
     *
     * Steps performed here:
     *  1. Save current song ID + seek position so the user can resume next time.
     *  2. Stop playback immediately.
     *  3. Remove the media notification and exit the foreground state.
     *  4. Stop the service, which triggers onDestroy → full resource release.
     *
     * This does NOT run when the user presses Home or switches apps — Android
     * only calls onTaskRemoved() on an explicit swipe-dismiss from Recents.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = mediaLibrarySession?.player ?: player

        // 1. Save last playback state (blocking, ~1 ms DataStore write — acceptable
        //    here because the service process is about to die anyway).
        val songId = currentPlayer.currentMediaItem?.mediaId
        if (!songId.isNullOrBlank()) {
            // Use coerceAtLeast(0) to avoid storing a negative position caused by
            // buffering states where currentPosition briefly returns -1.
            val positionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
            runBlocking(Dispatchers.IO) {
                try {
                    datastoreRepository.saveLastPlaybackState(songId, positionMs)
                } catch (e: Exception) {
                    // Non-fatal: if DataStore is unavailable the app still closes cleanly.
                    ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe(
                        "onTaskRemoved: could not save playback state: ${e.message}", exception = e
                    )
                }
            }
        }

        // 2. Stop playback so audio does not continue after the swipe.
        currentPlayer.stop()

        // 3. Dismiss the media notification immediately.
        //    On API 33+ use the explicit STOP_FOREGROUND_REMOVE flag; on older
        //    APIs use the deprecated boolean overload (still fully functional).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(/* removeNotification = */ true)
        }

        // 4. Stop the service — triggers onDestroy which releases ExoPlayer,
        //    audio focus, the MediaSession, and all registered callbacks.
        stopSelf()
    }

    // ── Connectivity helpers ──────────────────────────────────────────────────

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Registers a [ConnectivityManager.NetworkCallback] that resets [offlineNoticeShown] when
     * the device reconnects. This ensures the notice can appear again the next time the user
     * goes offline during the same app session.
     */
    private fun registerConnectivityObserver() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Internet is back — allow the offline notice to show again if needed.
                offlineNoticeShown = false
            }
        }
        cm.registerNetworkCallback(request, cb)
        connectivityCallback = cb
    }

    override fun onDestroy() {
        // Cancel all service-owned coroutines first so no background work (thumbnail
        // fetching, settings reads, etc.) races against resource teardown below.
        serviceScope.cancel()

        connectivityCallback?.let { cb ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        connectivityCallback = null

        mediaLibrarySession?.run {
            if (currentAudioSessionId > 0) {
                sendBroadcast(
                    Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, currentAudioSessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                    }
                )
            }
            player.release()
            exoCache.release()
            release()
            mediaLibrarySession = null
        }
        super.onDestroy()
    }

    private fun updateCurrentMediaItemThumbnail(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            return
        }

        val context = applicationContext
        val songId = mediaItem.mediaId

        serviceScope.launch {
            try {
                val imageDir = UmihiHelper.getDownloadDirectory(
                    context,
                    Constants.Downloads.THUMBNAILS_FOLDER
                )

                val downloadedImage = File(imageDir, "$songId.jpg")
                if (downloadedImage.exists()) {
                    val imageBytes = downloadedImage.readBytes()

                    updateMediaItemArtwork(
                        mediaItem,
                        imageBytes.cappedTo(),
                        downloadedImage.toUri()
                    )
                    return@launch
                }

                songRepository.getSongInfo(songId)
                    .collect { result ->
                        when (result) {
                            is ApiResult.Success -> {
                                val song = result.data
                                val thumbnail = song.thumbnailHref
                                if (thumbnail.isNotBlank()) {
                                    val artBytes = UmihiHelper.fetchArtworkBytes(thumbnail)
                                    if (artBytes != null) {
                                        updateMediaItemArtwork(
                                            mediaItem,
                                            artBytes,
                                            song.thumbnailHref.toUri()
                                        )
                                    }
                                    return@collect
                                }
                            }

                            is ApiResult.Error -> {
                                printe("Failed to fetch thumbnail for $songId from YouTube")
                                return@collect
                            }

                            else -> {}
                        }
                    }
            } catch (ex: Exception) {
                printe(
                    message = "Failed to get full res thumbnail for $songId. Error : ${ex.message}",
                )
            }
        }
    }

    private suspend fun updateMediaItemArtwork(
        mediaItem: MediaItem,
        artBytes: ByteArray?,
        uri: Uri
    ) {
        val extras = mediaItem.mediaMetadata.extras
        extras?.putString(
            Constants.ExoPlayer.SongMetadata.UID,
            Uuid.random().toString()
        )

        val updated = mediaItem.buildUpon()
            .setMediaMetadata(
                mediaItem.mediaMetadata.buildUpon()
                    .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setArtworkUri(uri)
                    .setExtras(extras)
                    .build()
            )
            .build()

        withContext(Dispatchers.Main) {
            if (player.currentMediaItem?.mediaId == mediaItem.mediaId) {
                player.replaceMediaItem(
                    player.currentMediaItemIndex,
                    updated
                )
            }
        }
    }
}