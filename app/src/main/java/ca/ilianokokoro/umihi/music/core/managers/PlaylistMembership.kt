package ca.ilianokokoro.umihi.music.core.managers

import android.content.Context
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * App-wide singleton that tracks which song IDs belong to at least one local playlist.
 *
 * Backed by Room's [PlaylistSongCrossRef] table — any insert or delete to that table
 * (including the optimistic updates in [PlaylistRepository.addSong] and
 * [PlaylistRepository.removeSong]) is reflected here immediately.
 *
 * Usage in a composable:
 * ```
 * val memberIds by PlaylistMembership.memberIds.collectAsStateWithLifecycle()
 * val isInPlaylist = song.youtubeId in memberIds
 * ```
 *
 * Call [initialize] once in [MainActivity.onCreate].
 */
object PlaylistMembership {

    private val _memberIds = MutableStateFlow<Set<String>>(emptySet())

    /** Live set of song IDs that belong to at least one playlist in the local database. */
    val memberIds: StateFlow<Set<String>> = _memberIds.asStateFlow()

    /**
     * Start observing the local database.
     * Should be called exactly once — on app start — with [lifecycleScope] as the scope.
     */
    fun initialize(context: Context, scope: CoroutineScope) {
        scope.launch {
            AppDatabase.getInstance(context)
                .playlistRepository()
                .observeAllCrossRefs()
                .map { refs -> refs.map { it.songId }.toSet() }
                .collect { ids -> _memberIds.value = ids }
        }
    }
}
