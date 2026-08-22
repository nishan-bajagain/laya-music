@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ca.ilianokokoro.umihi.music.ui.screens.library

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.ComposeHelper
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.ui.components.ErrorMessage
import ca.ilianokokoro.umihi.music.ui.components.FadingStatusBarWrapper
import ca.ilianokokoro.umihi.music.ui.components.LoadingAnimation
import ca.ilianokokoro.umihi.music.ui.components.dialog.PlaylistCreationDialog
import ca.ilianokokoro.umihi.music.ui.components.materialu.MaterialUButton
import ca.ilianokokoro.umihi.music.ui.components.playlist.PlaylistCard
import ca.ilianokokoro.umihi.music.ui.navigation.viewmodels.SharedViewModel
import androidx.compose.material3.MaterialTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sharedViewModel: SharedViewModel,
    onSettingsButtonPress: () -> Unit,
    onProfilePress: () -> Unit = {},
    onLogin: () -> Unit = {},
    onPlaylistPressed: (playlistInfo: PlaylistInfo) -> Unit,
    application: Application,
    libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.Factory(application = application)
    )
) {
    val uiState = libraryViewModel.uiState.collectAsStateWithLifecycle().value
    val downloadedSongCount = uiState.downloadedSongCount

    val downloadedPlaylistTitle = stringResource(R.string.downloaded_playlist_title)
    val downloadedPlaylistInfo = remember(downloadedPlaylistTitle) {
        PlaylistInfo(
            id = Constants.Downloads.DOWNLOADED_PLAYLIST_ID,
            title = downloadedPlaylistTitle,
        )
    }

    var createPlaylistOpen by remember { mutableStateOf(false) }

    val deletedPlaylistIds by sharedViewModel.deletedPlaylistIds.collectAsStateWithLifecycle()
    val playlistRefreshNeeded by sharedViewModel.playlistRefreshNeeded.collectAsStateWithLifecycle()


    LaunchedEffect(deletedPlaylistIds, playlistRefreshNeeded) {
        when {
            playlistRefreshNeeded -> {
                libraryViewModel.refreshPlaylists()
                sharedViewModel.consumePlaylistRefresh()
                sharedViewModel.consumeDeletedPlaylists()
            }
            deletedPlaylistIds.isNotEmpty() -> {
                libraryViewModel.removePlaylistsFromList(deletedPlaylistIds)
                sharedViewModel.consumeDeletedPlaylists()
            }
        }
    }

    FadingStatusBarWrapper { statusBarHeight ->
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.library)) })
            },
            contentWindowInsets = WindowInsets(0.dp),
        ) { paddingValues ->
            when (uiState.screenState) {
                is LibraryScreenState.LoggedIn -> {
                    val playlists = uiState.screenState.playlistInfos

                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = libraryViewModel::refreshPlaylists
                    ) {
                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxSize(),
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(
                                top = paddingValues.calculateTopPadding() + statusBarHeight + 8.dp,
                                bottom = Constants.Ui.SCROLLABLE_BOTTOM_PADDING,
                                end = 8.dp,
                                start = 8.dp
                            )
                        ) {
                            // Header: create playlist
                            item(
                                key = "library_header",
                                span = { GridItemSpan(maxLineSpan) },
                                contentType = "header"
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MaterialUButton(
                                        onClick = { createPlaylistOpen = true },
                                        icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                        text = stringResource(R.string.create_playlist)
                                    )
                                }
                            }

                            // Downloaded playlist card
                            item(
                                key = Constants.Downloads.DOWNLOADED_PLAYLIST_ID,
                                contentType = "playlist"
                            ) {
                                PlaylistCard(
                                    playlistInfo = downloadedPlaylistInfo,
                                    subtitle = stringResource(
                                        R.string.downloaded_playlist_subtitle,
                                        downloadedSongCount
                                    ),
                                    onClicked = { onPlaylistPressed(downloadedPlaylistInfo) }
                                )
                            }

                            if (playlists.isEmpty()) {
                                item(
                                    key = "library_empty_playlists",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "empty"
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp, horizontal = 16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Text(
                                            text = stringResource(R.string.no_playlists),
                                            textAlign = TextAlign.Center,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        TextButton(onClick = { createPlaylistOpen = true }) {
                                            Text(stringResource(R.string.create_playlist))
                                        }
                                    }
                                }
                            } else {
                                itemsIndexed(
                                    items = playlists,
                                    key = { index, playlist ->
                                        ComposeHelper.getLazyKey(playlist, playlist.id, index)
                                    },
                                    contentType = { _, _ -> "playlist" }
                                ) { _, playlist ->
                                    PlaylistCard(
                                        playlistInfo = playlist,
                                        onClicked = { onPlaylistPressed(playlist) }
                                    )
                                }
                            }
                        }
                    }
                }

                LibraryScreenState.LoggedOut -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(paddingValues),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(0.45f)
                        ) {
                            PlaylistCard(
                                playlistInfo = downloadedPlaylistInfo,
                                subtitle = stringResource(
                                    R.string.downloaded_playlist_subtitle,
                                    downloadedSongCount
                                ),
                                onClicked = { onPlaylistPressed(downloadedPlaylistInfo) }
                            )
                        }
                        androidx.compose.material3.Text(
                            stringResource(R.string.log_in_message),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                LibraryScreenState.Loading -> LoadingAnimation()
                is LibraryScreenState.Error -> ErrorMessage(
                    ex = uiState.screenState.exception,
                    onRetry = libraryViewModel::getPlaylists
                )
            }

            if (createPlaylistOpen) {
                PlaylistCreationDialog(
                    onClose = { createPlaylistOpen = false },
                    onConfirm = { title, description, privacy ->
                        libraryViewModel.createPlaylist(title, description, privacy)
                        createPlaylistOpen = false
                    }
                )
            }
        }
    }
}
