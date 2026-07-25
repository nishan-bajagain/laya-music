@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ca.ilianokokoro.umihi.music.ui.screens.home

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(
    sharedViewModel: SharedViewModel,
    onSettingsButtonPress: () -> Unit,
    onProfilePress: () -> Unit = {},
    onLogin: () -> Unit = {},
    onPlaylistPressed: (playlistInfo: PlaylistInfo) -> Unit,
    application: Application,
    homeViewModel: HomeViewModel = viewModel(
        factory =
            HomeViewModel.Factory(application = application)
    )

) {
    val uiState = homeViewModel.uiState.collectAsStateWithLifecycle().value
    val downloadedSongCount = uiState.downloadedSongCount

    // Static PlaylistInfo for the local "Downloads" playlist — always shown regardless of login.
    val downloadedPlaylistTitle = stringResource(R.string.downloaded_playlist_title)
    val downloadedPlaylistInfo = remember(downloadedPlaylistTitle) {
        PlaylistInfo(
            id = Constants.Downloads.DOWNLOADED_PLAYLIST_ID,
            title = downloadedPlaylistTitle,
        )
    }

    var createPlaylistOpen by remember { mutableStateOf(false) }

    val deletedPlaylistIds by sharedViewModel.deletedPlaylistIds.collectAsState()
    val playlistRefreshNeeded by sharedViewModel.playlistRefreshNeeded.collectAsState()

    val context = LocalContext.current
    val datastoreRepository = remember { DatastoreRepository(context) }
    val accountAvatarUrl by datastoreRepository.accountAvatarUrl.collectAsState(initial = "")
    val cookies by datastoreRepository.cookies.collectAsState(initial = ca.ilianokokoro.umihi.music.models.Cookies())
    val isLoggedIn = cookies.isNotEmpty()

    LaunchedEffect(deletedPlaylistIds, playlistRefreshNeeded) {
        when {
            playlistRefreshNeeded -> {
                homeViewModel.refreshPlaylists()
                sharedViewModel.consumePlaylistRefresh()
                sharedViewModel.consumeDeletedPlaylists()
            }

            deletedPlaylistIds.isNotEmpty() -> {
                homeViewModel.removePlaylistsFromList(deletedPlaylistIds)
                sharedViewModel.consumeDeletedPlaylists()
            }
        }
    }

    FadingStatusBarWrapper { statusBarHeight ->
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (uiState.screenState) {
                    is ScreenState.LoggedIn -> {
                        val playlists = uiState.screenState.playlistInfos

                        // Always show the full grid — even when there are no remote playlists.
                        // This keeps the Downloads card, profile button and create-playlist
                        // button accessible in all logged-in states (e.g. API failure with
                        // empty local cache, or a brand-new account with no playlists yet).
                        PullToRefreshBox(
                            isRefreshing = uiState.isRefreshing,
                            onRefresh = homeViewModel::refreshPlaylists
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
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Profile avatar button (left)
                                        IconButton(onClick = onProfilePress) {
                                            if (isLoggedIn && accountAvatarUrl.isNotBlank()) {
                                                AsyncImage(
                                                    model = accountAvatarUrl,
                                                    contentDescription = "Profile",
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(CircleShape),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Outlined.AccountCircle,
                                                    contentDescription = "Profile",
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }

                                        // Create playlist button (right)
                                        MaterialUButton(
                                            onClick = { createPlaylistOpen = true },
                                            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                            text = stringResource(R.string.create_playlist)
                                        )
                                    }
                                }

                                // Downloaded playlist — always the first card in the grid.
                                item(key = Constants.Downloads.DOWNLOADED_PLAYLIST_ID) {
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
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Text(
                                            stringResource(R.string.no_playlists),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    itemsIndexed(
                                        items = playlists,
                                        key = { index, playlist ->
                                            ComposeHelper.getLazyKey(
                                                playlist,
                                                playlist.id,
                                                index
                                            )
                                        }
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

                    ScreenState.LoggedOut -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Show the Downloads card even when not logged in — downloaded songs
                        // are always accessible offline.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.45f)
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

                        Text(
                            stringResource(R.string.log_in_message),
                            textAlign = TextAlign.Center
                        )
                        androidx.compose.material3.Button(
                            onClick = onLogin,
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(stringResource(R.string.log_in))
                        }
                        FilledTonalButton(
                            onClick = onSettingsButtonPress,
                            shapes = ButtonDefaults.shapes()
                        ) {
                            Text(stringResource(R.string.open_settings))
                        }
                    }

                    ScreenState.Loading -> LoadingAnimation()
                    is ScreenState.Error -> ErrorMessage(
                        ex = uiState.screenState.exception,
                        onRetry = homeViewModel::getPlaylists
                    )

                }
                if (createPlaylistOpen) {
                    PlaylistCreationDialog(
                        onClose = { createPlaylistOpen = false },
                        onConfirm = { title, description, privacy ->
                            homeViewModel.createPlaylist(title, description, privacy)
                            createPlaylistOpen = false
                        })

                }
            }
        }
    }

}
