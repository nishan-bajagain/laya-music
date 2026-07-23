package ca.ilianokokoro.umihi.music.ui.screens.search

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.managers.PlaylistMembership
import ca.ilianokokoro.umihi.music.data.repositories.DownloadRepository
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.ui.components.AddToPlaylistBottomSheet
import ca.ilianokokoro.umihi.music.ui.components.ErrorMessage
import ca.ilianokokoro.umihi.music.ui.components.LoadingAnimation
import ca.ilianokokoro.umihi.music.ui.components.SongManagementBottomSheet
import ca.ilianokokoro.umihi.music.ui.components.song.SongListItem
import ca.ilianokokoro.umihi.music.ui.screens.search.components.SearchBar
import kotlinx.coroutines.launch


@Composable
fun SearchScreen(
    application: Application,
    searchViewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.Factory(application = application)
    )
) {
    val uiState = searchViewModel.uiState.collectAsStateWithLifecycle().value

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadRepository = remember { DownloadRepository(application) }

    // Global playlist membership — updated reactively via Room Flow
    val memberIds by PlaylistMembership.memberIds.collectAsStateWithLifecycle()

    // Sheet state
    var addToPlaylistSong by remember { mutableStateOf<Song?>(null) }
    var managementSong by remember { mutableStateOf<Song?>(null) }

    val isSearching = uiState.search.isNotBlank()

    LaunchedEffect(Unit) {
        if (!isSearching) focusRequester.requestFocus()
    }

    // Management sheet — shown when the song already belongs to a playlist
    managementSong?.let { song ->
        SongManagementBottomSheet(
            song = song,
            onDismiss = { managementSong = null },
            onAddToAnotherPlaylist = { addToPlaylistSong = song },
            onMembershipChanged = { /* PlaylistMembership updates reactively */ }
        )
    }

    // Add-to-playlist sheet — shown when the song is not yet in any playlist,
    // or when coming from "Add to another playlist" in the management sheet
    addToPlaylistSong?.let { song ->
        AddToPlaylistBottomSheet(
            song = song,
            memberPlaylistIds = memberIds,
            onDismiss = { addToPlaylistSong = null }
        )
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(top = paddingValues.calculateTopPadding())
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Search bar
            SearchBar(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                value = uiState.search,
                onValueChange = searchViewModel::onSearchFieldChange,
                onSearch = searchViewModel::search,
                focusManager = focusManager,
                focusRequester = focusRequester,
            )

            // Content
            when {
                // ── Active search ─────────────────────────────────────────────
                isSearching -> {
                    if (uiState.screenState is ScreenState.Error) {
                        ErrorMessage(
                            ex = uiState.screenState.exception,
                            onRetry = searchViewModel::search
                        )
                    } else {
                        when (uiState.screenState) {
                            ScreenState.Loading -> LoadingAnimation()

                            is ScreenState.Success -> {
                                val songs = uiState.screenState.results
                                if (songs.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(stringResource(R.string.no_results))
                                    }
                                } else {
                                    SongList(
                                        songs = songs,
                                        memberIds = memberIds,
                                        context = context,
                                        application = application,
                                        downloadRepository = downloadRepository,
                                        scope = scope,
                                        onAddToPlaylist = { addToPlaylistSong = it },
                                        onManagePlaylist = { managementSong = it }
                                    )
                                }
                            }

                            is ScreenState.Error -> { /* handled above */ }
                        }
                    }
                }

                // ── Recommendations (shown when search box is empty) ──────────
                else -> {
                    if (uiState.recommendationsLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (uiState.recommendations.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.start_listening),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.no_recommendations),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = Constants.Ui.SCROLLABLE_BOTTOM_PADDING)
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.recommendations_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp
                                    )
                                )
                            }
                            val recommendations = uiState.recommendations
                            items(
                                items = recommendations,
                                key = { it.uid }
                            ) { song ->
                                val inPlaylist = song.youtubeId in memberIds
                                SongListItem(
                                    song = song,
                                    onPress = {
                                        val idx = recommendations.indexOf(song).coerceAtLeast(0)
                                        PlayerManager.playQueue(recommendations.map { it.mediaItem }, idx)
                                    },
                                    playNext = { PlayerManager.addNext(song, context) },
                                    addToQueue = { PlayerManager.addToQueue(song, context) },
                                    download = {
                                        scope.launch {
                                            downloadRepository.downloadSongStandalone(song)
                                        }
                                    },
                                    isInPlaylist = inPlaylist,
                                    addToPlaylist = if (!inPlaylist) {
                                        { addToPlaylistSong = song }
                                    } else null,
                                    onManagePlaylist = if (inPlaylist) {
                                        { managementSong = song }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    memberIds: Set<String>,
    context: android.content.Context,
    application: Application,
    downloadRepository: DownloadRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onAddToPlaylist: (Song) -> Unit,
    onManagePlaylist: (Song) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = Constants.Ui.SCROLLABLE_BOTTOM_PADDING),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = songs, key = { it.uid }) { song ->
            val inPlaylist = song.youtubeId in memberIds
            SongListItem(
                song = song,
                onPress = {
                    val idx = songs.indexOf(song).coerceAtLeast(0)
                    PlayerManager.playQueue(songs.map { it.mediaItem }, idx)
                },
                playNext = { PlayerManager.addNext(song, context) },
                addToQueue = { PlayerManager.addToQueue(song, context) },
                download = {
                    scope.launch { downloadRepository.downloadSongStandalone(song) }
                },
                isInPlaylist = inPlaylist,
                addToPlaylist = if (!inPlaylist) {
                    { onAddToPlaylist(song) }
                } else null,
                onManagePlaylist = if (inPlaylist) {
                    { onManagePlaylist(song) }
                } else null
            )
        }
    }
}
