package ca.ilianokokoro.umihi.music.core.managers

import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.models.Playlist
import ca.ilianokokoro.umihi.music.models.Song
import kotlin.math.abs
import java.io.File
import android.app.NotificationManager as AndroidNotificationManager

object NotificationManager {
    private lateinit var androidNotificationManager: AndroidNotificationManager
    private lateinit var pendingIntent: PendingIntent

    fun init(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        androidNotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannels.entries.forEach {
                val notificationChannel = NotificationChannel(
                    it.channelId,
                    context.getString(it.nameRes),
                    it.importance
                ).apply {
                    description = context.getString(it.descriptionRes)
                }
                androidNotificationManager.createNotificationChannel(notificationChannel)
            }
        } else {
            printe("Could not start the notification channels because the android version is too old")
        }
    }

    fun showPlaylistDownloadWaitingForWifi(
        context: Context,
        playlist: Playlist,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(
                context.getString(
                    R.string.waiting_for_wifi_to_download,
                    playlist.info.title
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadProgress(
        context: Context,
        playlist: Playlist,
        currentSong: Int,
        totalSongs: Int
    ) {

        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(
                context.getString(
                    R.string.number_of_songs_downloaded,
                    currentSong,
                    totalSongs
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(totalSongs, currentSong, false)
            .setOngoing(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadSuccess(
        context: Context,
        playlist: Playlist,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(context.getString(R.string.playlist_downloaded))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadFailure(
        context: Context,
        playlist: Playlist,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText(
                context.getString(
                    R.string.failed_to_download_playlist,
                    playlist.info.title
                )
            )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    fun showPlaylistDownloadCanceled(
        context: Context,
        playlist: Playlist
    ) {
        val notification = getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
            .setContentTitle(playlist.info.title)
            .setContentText(context.getString(R.string.download_canceled))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(playlist.info.id), notification)
        updateGroupSummary(context)
    }

    private fun updateGroupSummary(context: Context) {
        val summaryNotification =
            getBaseNotification(context, NotificationChannels.PLAYLIST_DOWNLOAD)
                .setContentTitle(context.getString(R.string.download_finished))
                .setContentText(String())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setGroup(NotificationChannels.PLAYLIST_DOWNLOAD.group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        androidNotificationManager.notify(0, summaryNotification)
    }


    fun showSongDownloadWaitingForWifi(
        context: Context,
        song: Song,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(song.title)
            .setContentText(context.getString(R.string.waiting_for_wifi_to_download, song.title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)
    }

    fun showSongDownloadFailed(
        context: Context,
        song: Song,
    ) {
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText(
                context.getString(
                    R.string.failed_to_download_song,
                    song.title,
                    song.artist
                )
            )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)
    }

    suspend fun showSongDownloadSuccess(
        context: Context,
        song: Song,
    ) {
        // getThumbnailBitmap() is only used here, so the returned bitmap is not
        // shared anywhere else — it is safe to recycle the local reference below.
        val largeIcon = song.getThumbnailBitmap()
        val notification = getBaseNotification(context, NotificationChannels.SONG_DOWNLOAD)
            .setContentTitle(song.title)
            .setContentText(context.getString(R.string.song_downloaded))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setLargeIcon(largeIcon)
            .setAutoCancel(true)
            .setGroup(NotificationChannels.SONG_DOWNLOAD.group)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID(song.youtubeId), notification)

        // The framework has parceled the bitmap into the notification by now, so
        // the local reference is no longer needed. Recycling it eagerly matters
        // during a bulk playlist download, where one thumbnail per song would
        // otherwise stay allocated until GC.
        largeIcon?.let {
            if (!it.isRecycled) it.recycle()
        }
    }

    fun showUpdateWaitingForWifi(context: Context) {
        val notification = getBaseNotification(context, NotificationChannels.APP_UPDATE)
            .setContentTitle(context.getString(R.string.update_available))
            .setContentText(context.getString(R.string.update_waiting_for_wifi))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID("app_update"), notification)
    }

    fun showUpdateDownloadProgress(
        context: Context,
        progress: Long,
        total: Long
    ) {
        val percent = if (total > 0) ((progress * 100) / total).toInt() else 0
        val notification = getBaseNotification(context, NotificationChannels.APP_UPDATE)
            .setContentTitle(context.getString(R.string.downloading_update))
            .setContentText(
                context.getString(
                    R.string.update_download_progress_notification,
                    percent
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        androidNotificationManager.notify(getNotificationID("app_update"), notification)
    }

    fun showUpdateReadyToInstall(
        context: Context,
        apkFile: File,
        version: String
    ) {
        // Tap the notification to jump straight into the install screen — but
        // only when the update carries the same signing certificate as the
        // installed app. Otherwise Android would show a bare "package
        // conflicts" error; open the app so the guided migration dialog
        // appears instead.
        val canInstallInPlace = SigningUtils.signaturesMatch(context, apkFile) != false
        val contentIntent = if (canInstallInPlace) {
            UpdateManager.buildInstallIntent(context, apkFile)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                ?: UpdateManager.buildInstallIntent(context, apkFile)
        }
        val installPendingIntent = PendingIntent.getActivity(
            context,
            UPDATE_INSTALL_REQUEST_CODE,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = getBaseNotification(context, NotificationChannels.APP_UPDATE)
            .setContentTitle(context.getString(R.string.update_ready_notification_title))
            .setContentText(
                context.getString(
                    R.string.update_ready_notification_text,
                    version
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(installPendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        androidNotificationManager.notify(getNotificationID("app_update"), notification)
    }

    fun showUpdateDownloadFailed(context: Context) {
        val notification = getBaseNotification(context, NotificationChannels.APP_UPDATE)
            .setContentTitle(context.getString(R.string.update_download_failed_notification))
            .setContentText(context.getString(R.string.update_download_failed))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        androidNotificationManager.notify(getNotificationID("app_update"), notification)
    }

    private fun getBaseNotification(
        context: Context,
        channel: NotificationChannels
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(
            context,
            channel.channelId
        )
            .setContentIntent(pendingIntent)
    }

    private fun getNotificationID(id: String): Int {
        return 1000 + abs(id.hashCode() and 0x7fffffff)
    }


    private enum class NotificationChannels(
        val channelId: String,
        @param:StringRes val nameRes: Int,
        @param:StringRes val descriptionRes: Int,
        val importance: Int,
        val group: String
    ) {

        PLAYLIST_DOWNLOAD(
            channelId = "playlist_progress",
            nameRes = R.string.playlist_progress_name,
            descriptionRes = R.string.playlist_progress_description,
            importance = AndroidNotificationManager.IMPORTANCE_LOW,
            group = "PLAYLIST_GROUP"
        ),

        SONG_DOWNLOAD(
            channelId = "song_alerts",
            nameRes = (R.string.song_alerts_name),
            descriptionRes = (R.string.song_alerts_description),
            importance = AndroidNotificationManager.IMPORTANCE_DEFAULT,
            group = "SONG_GROUP"
        ),

        APP_UPDATE(
            channelId = "app_update",
            nameRes = R.string.app_update_name,
            descriptionRes = R.string.app_update_description,
            importance = AndroidNotificationManager.IMPORTANCE_DEFAULT,
            group = "APP_UPDATE_GROUP"
        );
    }

    private const val UPDATE_INSTALL_REQUEST_CODE = 42
}