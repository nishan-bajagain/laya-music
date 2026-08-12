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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.models.PlaylistInfo
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.ui.components.ErrorMessage
import ca.ilianokokoro.umihi.music.ui.components.FadingStatusBarWrapper
import ca.ilianokokoro.umihi.music.ui.components.LoadingAnimation
import ca.ilianokokoro.umihi.music.ui.components.dialog.PlaylistCreationDialog
import ca.ilianokokoro.umihi.music.ui.components.materialu.MaterialUButton
import ca.ilianokokoro.umihi.music.ui.components.playlist.PlaylistCard
import ca.ilianokokoro.umihi.music.ui.navigation.viewmodels.SharedViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File

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

    val deletedPlaylistIds by sharedViewModel.deletedPlaylistIds.collectAsStateWithLifecycle()
    val playlistRefreshNeeded by sharedViewModel.playlistRefreshNeeded.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val datastoreRepository = remember { DatastoreRepository(context) }
    val accountAvatarUrl by datastoreRepository.accountAvatarUrl.collectAsStateWithLifecycle(initialValue = "")
    val cookies by datastoreRepository.cookies.collectAsStateWithLifecycle(initialValue = ca.ilianokokoro.umihi.music.models.Cookies())
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
                                item(
                                    key = "home_header",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "header"
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Profile avatar button (left)
                                        IconButton(onClick = onProfilePress) {
                                            if (isLoggedIn && accountAvatarUrl.isNotBlank()) {
                                                val avatarRequest = remember(accountAvatarUrl) {
                                                    ImageRequest.Builder(context)
                                                        .data(accountAvatarUrl)
                                                        // 36dp avatar ≈ 108px @3x — bound the
                                                        // decode well below the raw URL size.
                                                        .size(96, 96)
                                                        .build()
                                                }
                                                AsyncImage(
                                                    model = avatarRequest,
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

                                item(
                                    key = "home_recommendations",
                                    span = { GridItemSpan(maxLineSpan) },
                                    contentType = "recommendation_rail"
                                ) {
                                    HomeRecommendationRail(
                                        songs = uiState.recommendations,
                                        loading = uiState.recommendationsLoading,
                                        onSongPressed = { song ->
                                            val index = uiState.recommendations.indexOfFirst {
                                                it.youtubeId == song.youtubeId
                                            }.coerceAtLeast(0)
                                            PlayerManager.playQueue(
                                                uiState.recommendations.map { it.mediaItem },
                                                index
                                            )
                                        }
                                    )
                                }

                                // Downloaded playlist — always the first card in the grid.
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
                                        key = "home_empty_playlists",
                                        span = { GridItemSpan(maxLineSpan) },
                                        contentType = "empty"
                                    ) {
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

@Composable
private fun HomeRecommendationRail(
    songs: List<Song>,
    loading: Boolean,
    onSongPressed: (Song) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.home_recommendations_title),
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        when {
            loading -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(4) { RecommendationSkeletonCard() }
                }
            }

            songs.isEmpty() -> {
                Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.home_recommendations_empty),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            else -> {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(songs, key = { it.youtubeId }) { song ->
                        RecommendationCard(
                            song = song,
                            onClick = { onSongPressed(song) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    song: Song,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var retryToken by remember(song.thumbnailPath, song.thumbnailHref) { mutableIntStateOf(0) }
    var failed by remember(song.thumbnailPath, song.thumbnailHref) { mutableStateOf(false) }

    // Prefer the local cached copy, but if it's missing/corrupt, fall back to
    // the remote URL instead of failing outright.
    val primaryUrl = song.thumbnailPath?.takeIf { File(it).exists() } ?: song.thumbnailHref

    val imageRequest = remember(primaryUrl, retryToken) {
        ImageRequest.Builder(context)
            .data(if (retryToken == 0) primaryUrl else song.thumbnailHref)
            .size(384, 384)
            .build()
    }

    Card(
        onClick = onClick,
        modifier = Modifier.width(152.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(152.dp)) {
            // Base layer so a failed/loading image never leaves a blank card.
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
                tint = androidx.compose.material3.MaterialTheme.colorScheme
                    .onSurfaceVariant
                    .copy(alpha = 0.4f)
            )
            if (!failed) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = {
                        if (retryToken == 0 && primaryUrl != song.thumbnailHref) {
                            // local file failed — retry against the remote URL once
                            retryToken = 1
                        } else {
                            failed = true
                        }
                    }
                )
            }
        }
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = song.title,
                style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecommendationSkeletonCard() {
    Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(152.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
            ) {}
            Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
            ) {}
            Surface(
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(start = 10.dp, end = 28.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .height(11.dp)
            ) {}
        }
    }
}
