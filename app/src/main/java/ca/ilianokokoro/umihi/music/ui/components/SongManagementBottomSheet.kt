package ca.ilianokokoro.umihi.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.PlaylistRepository
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Song
import kotlinx.coroutines.launch

/**
 * Bottom sheet that shows all playlists a [song] currently belongs to and lets the user:
 *  - Remove the song from any of them.
 *  - Tap "Add to another playlist" to be handed off to [AddToPlaylistBottomSheet].
 *
 * @param onAddToAnotherPlaylist Called when the user wants to add the song to a playlist it is
 *   not yet in. The caller is responsible for opening [AddToPlaylistBottomSheet].
 * @param onMembershipChanged   Called after any remove so parent screens can refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongManagementBottomSheet(
    song: Song,
    onDismiss: () -> Unit,
    onAddToAnotherPlaylist: () -> Unit = {},
    onMembershipChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val scope = rememberCoroutineScope()

    var playlists by remember { mutableStateOf<List<PlaylistInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var removingPlaylistId by remember { mutableStateOf<String?>(null) }

    // Load playlists this song belongs to from the local DB.
    LaunchedEffect(Unit) {
        playlists = AppDatabase.getInstance(context)
            .playlistRepository()
            .getPlaylistInfosForSong(song.youtubeId)
        isLoading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.manage_playlists),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            playlists.isEmpty() -> {
                // Edge case: song is tracked as a member but local DB has no record.
                // Show the add option so the user isn't stuck.
                ListItem(
                    leadingContent = {
                        Icon(Icons.Rounded.LibraryAdd, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        onAddToAnotherPlaylist()
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.add_to_playlist))
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.padding(bottom = 16.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val isRemoving = removingPlaylistId == playlist.id

                        ListItem(
                            leadingContent = {
                                SquareImage(
                                    uri = playlist.coverPath ?: playlist.coverHref,
                                    modifier = Modifier.size(48.dp)
                                )
                            },
                            trailingContent = {
                                if (isRemoving) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                } else {
                                    IconButton(onClick = {
                                        scope.launch {
                                            removingPlaylistId = playlist.id
                                            try {
                                                val settings =
                                                    DatastoreRepository(context).getSettings()
                                                PlaylistRepository(application).removeSong(
                                                    playlistId = playlist.id,
                                                    videoId = song.youtubeId,
                                                    setVideoId = song.setVideoId,
                                                    settings = settings
                                                ).collect { /* fire and forget */ }

                                                // Reload the list after removal
                                                playlists = AppDatabase.getInstance(context)
                                                    .playlistRepository()
                                                    .getPlaylistInfosForSong(song.youtubeId)

                                                onMembershipChanged()
                                            } catch (_: Exception) {
                                                // Leave the list unchanged on failure
                                            } finally {
                                                removingPlaylistId = null
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlaylistRemove,
                                            contentDescription = stringResource(R.string.remove_from_playlist),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(text = playlist.title)
                        }
                    }

                    // "Add to another playlist" row
                    item {
                        ListItem(
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.LibraryAdd,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            modifier = Modifier.clickable {
                                onAddToAnotherPlaylist()
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.add_to_another_playlist),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
