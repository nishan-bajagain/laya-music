package ca.ilianokokoro.umihi.music.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
@Immutable
@Entity(tableName = Constants.Database.SONGS_TABLE)
data class Song(
    @PrimaryKey
    val youtubeId: String,
    val title: String = "",
    val artist: String = "",
    val duration: String = "",
    val thumbnailHref: String = "",
    val thumbnailPath: String? = null,
    val streamUrl: String? = null,
    val audioFilePath: String? = null,
    val uid: String = Uuid.random().toString(),
    val isExplicit: Boolean = false,
    val isLiked: Boolean? = null,
) {
    /**
     * Per-entry playlist token returned by the YT Music API as `playlistSetVideoId`.
     * Required by `ACTION_REMOVE_VIDEO` to reliably remove a song from a playlist.
     * Not persisted to DB — populated in-memory from the remote playlist response.
     * Declared outside the primary constructor so Room/KSP does not try to map it
     * to a column (KSP rejects @Ignore parameters in data-class primary constructors).
     */
    @Ignore
    var setVideoId: String? = null

    /**
     * Cached UID for the MediaItem extras. Previously `Uuid.random().toString()` was
     * called on every `mediaItem` access, causing object churn and unnecessary
     * recompositions when the queue was rebuilt (e.g. in `PlayerManager.getQueue()`).
     * Caching it here means the same Song instance always produces a stable MediaItem.
     */
    // Note: @Ignore is not applicable to delegated properties, but since this is
    // a private val with no backing field column, Room won't try to map it anyway.
    private val cachedMediaItemUid: String by lazy { Uuid.random().toString() }

    val mediaItem: MediaItem
        get() {
            val extras = Bundle()
            extras.putString(Constants.ExoPlayer.SongMetadata.DURATION, duration)
            extras.putString(Constants.ExoPlayer.SongMetadata.UID, cachedMediaItemUid)
            extras.putBoolean(Constants.ExoPlayer.SongMetadata.IS_EXPLICIT, isExplicit)
            isLiked?.let { extras.putBoolean(Constants.ExoPlayer.SongMetadata.IS_LIKED, it) }

            return MediaItem.Builder()
                .setUri(youtubeUrl)
                .setMediaId(youtubeId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setArtworkUri((thumbnailPath ?: thumbnailHref).toUri())
                        .setExtras(extras)
                        .build()

                )
                .build()
        }


    val youtubeUrl: String
        get() = "${Constants.YoutubeApi.YOUTUBE_URL_PREFIX}${youtubeId}"
    val downloaded: Boolean
        get() = audioFilePath != null && thumbnailPath != null


    /**
     * Fetches the remote artwork and decodes it downsampled to ~256x256 — a
     * notification large icon only needs that much, and decoding the raw
     * maxresdefault (~1920x1080) would allocate a multi-MB ARGB_8888 bitmap
     * per download notification.
     */
    suspend fun getThumbnailBitmap(): Bitmap? {
        val bytes = UmihiHelper.fetchArtworkBytes(thumbnailHref) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = UmihiHelper.calculateInSampleSize(
            bounds,
            reqWidth = 256,
            reqHeight = 256
        )
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Song) return false
        // Field-by-field equality guarantees correctness — hashing alone can
        // produce false positives due to hash collisions.
        return youtubeId == other.youtubeId &&
            title == other.title &&
            artist == other.artist &&
            duration == other.duration &&
            thumbnailHref == other.thumbnailHref &&
            thumbnailPath == other.thumbnailPath &&
            streamUrl == other.streamUrl &&
            audioFilePath == other.audioFilePath &&
            uid == other.uid &&
            isExplicit == other.isExplicit &&
            isLiked == other.isLiked
    }

    override fun hashCode(): Int {
        var result = youtubeId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + thumbnailHref.hashCode()
        result = 31 * result + (thumbnailPath?.hashCode() ?: 0)
        result = 31 * result + (streamUrl?.hashCode() ?: 0)
        result = 31 * result + (audioFilePath?.hashCode() ?: 0)
        result = 31 * result + uid.hashCode()
        result = 31 * result + isExplicit.hashCode()
        result = 31 * result + (isLiked?.hashCode() ?: 0)
        return result
    }

    fun isSameYoutubeSong(other: Song): Boolean {
        return this.youtubeId == other.youtubeId
    }

    companion object {
        fun createFromYoutubeUrl(url: String): Song {
            return Song(youtubeId = url.removePrefix(Constants.YoutubeApi.YOUTUBE_URL_PREFIX))
        }

    }

}


