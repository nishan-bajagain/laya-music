package ca.ilianokokoro.umihi.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import ca.ilianokokoro.umihi.music.data.datasources.PlaylistDataSource
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.data.repositories.PlaylistRepository
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.models.UmihiSettings
import kotlinx.coroutines.launch

/**
 * Bottom sheet that lets the user add [song] to one of their YouTube Music playlists.
 *
 * @param memberPlaylistIds  IDs of playlists the song already belongs to (shown with a checkmark,
 *                           cannot be tapped). Normalised (VL-prefix stripped) before comparison.
 * @param onAdded            Called after the song is successfully added to a playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    song: Song,
    onDismiss: () -> Unit,
    onAdded: () -> Unit = {},
    /**
     * Replaces the old `currentPlaylistId: String?` parameter.
     * Pass all playlist IDs the song currently belongs to so every already-joined playlist
     * shows a checkmark (not just a single one).
     */
    memberPlaylistIds: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    var playlists by remember { mutableStateOf<List<PlaylistInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoggedIn by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf<UmihiSettings?>(null) }
    var addingPlaylistId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val ds = DatastoreRepository(context)
            val s = ds.getSettings()
            settings = s

            if (s.cookies.toRawCookie().isBlank()) {
                isLoggedIn = false
                isLoading = false
                return@LaunchedEffect
            }

            val source = PlaylistDataSource()
            playlists = source.retrieveAll(s)
        } catch (_: Exception) {
            // leave playlists empty; user will see "no playlists" message
        }
        isLoading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.add_to_playlist),
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

            !isLoggedIn -> {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.log_in_to_add_to_playlist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            playlists.isEmpty() -> {
                Text(
                    text = stringResource(R.string.no_playlists),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        // Strip the "VL" prefix YouTube sometimes prepends before comparing
                        val normalizedPlaylist = playlist.id.removePrefix("VL")
                        val isMember = memberPlaylistIds.any { memberId ->
                            memberId.removePrefix("VL") == normalizedPlaylist
                        }
                        val isBeingAdded = addingPlaylistId == playlist.id

                        ListItem(
                            leadingContent = {
                                SquareImage(
                                    uri = playlist.coverPath ?: playlist.coverHref,
                                    modifier = Modifier.size(48.dp)
                                )
                            },
                            trailingContent = when {
                                isBeingAdded -> {
                                    { CircularProgressIndicator(modifier = Modifier.size(20.dp)) }
                                }
                                isMember -> {
                                    {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                else -> null
                            },
                            modifier = Modifier.clickable(
                                enabled = !isMember && !isBeingAdded
                            ) {
                                scope.launch {
                                    val s = settings ?: return@launch
                                    addingPlaylistId = playlist.id
                                    try {
                                        PlaylistRepository(application).addSong(
                                            playlistId = playlist.id,
                                            videoId = song.youtubeId,
                                            settings = s
                                        ).collect { /* wait for completion */ }
                                        onAdded()
                                        onDismiss()
                                    } catch (_: Exception) {
                                        addingPlaylistId = null
                                    }
                                }
                            },
                        ) {
                            Text(
                                text = playlist.title,
                                color = if (isMember) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
