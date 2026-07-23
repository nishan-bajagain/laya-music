@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package ca.ilianokokoro.umihi.music.ui.screens.player

import android.app.Application
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.core.managers.PlaylistMembership
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.ui.components.AddToPlaylistBottomSheet
import ca.ilianokokoro.umihi.music.ui.components.SongManagementBottomSheet
import ca.ilianokokoro.umihi.music.ui.components.SquareImage
import ca.ilianokokoro.umihi.music.ui.screens.lyrics.LyricsScreen
import ca.ilianokokoro.umihi.music.ui.screens.player.components.PlayerControls
import ca.ilianokokoro.umihi.music.ui.screens.player.components.QueueBottomSheet
import ca.ilianokokoro.umihi.music.ui.screens.player.components.SleepTimerBottomSheet
import ca.ilianokokoro.umihi.music.ui.screens.player.components.SpeedSelectorBottomSheet
import androidx.compose.runtime.rememberCoroutineScope
import androidx.work.WorkInfo
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DownloadRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    application: Application,
    playerViewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(application = application)
    )
) {
    val uiState = playerViewModel.uiState.collectAsStateWithLifecycle().value
    val playbackData = PlayerManager.audioInfo.collectAsStateWithLifecycle().value
    val orientation = LocalConfiguration.current.orientation
    val currentSong = uiState.queue.getOrNull(uiState.currentIndex)

    var showLyrics by remember { mutableStateOf(false) }

    // Global playlist membership
    val memberIds by PlaylistMembership.memberIds.collectAsStateWithLifecycle()
    val isCurrentSongInPlaylist = currentSong?.youtubeId?.let { it in memberIds } == true

    // Sheet state for playlist management / add-to-playlist
    var showManagementSheet by remember { mutableStateOf(false) }
    var showAddToPlaylistSheet by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val downloadRepo = remember { DownloadRepository(application) }

    // Observe WorkManager state for the current song's standalone download so that
    // the download state survives navigation (no local boolean that resets on recomposition).
    val standaloneWorkInfos by remember(currentSong?.youtubeId) {
        currentSong?.youtubeId?.let { downloadRepo.getStandaloneWorkInfoFlow(it) }
            ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val isDownloading = standaloneWorkInfos.any {
        it.state == WorkInfo.State.ENQUEUED ||
        it.state == WorkInfo.State.RUNNING ||
        it.state == WorkInfo.State.BLOCKED
    }

    // The Song objects in the player queue come from MediaItem (ExoPlayer) and never
    // carry audioFilePath / thumbnailPath, so currentSong.downloaded is always false.
    // Observe the DB directly — the same source the Downloads list uses — keyed by
    // youtubeId so it updates reactively the moment a download completes.
    val db = remember { AppDatabase.getInstance(application) }
    val downloadedSongIds by remember {
        db.songRepository().observeDownloadedSongs()
            .map { songs -> songs.map { it.youtubeId }.toSet() }
    }.collectAsStateWithLifecycle(initialValue = emptySet())
    val isDownloaded = currentSong?.youtubeId?.let { it in downloadedSongIds } == true

    // Close the screen in resumed with an empty queue
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && uiState.queue.isEmpty() && currentSong == null) {
                onBack()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.padding(
            start = 8.dp,
            end = 8.dp,
            bottom = 10.dp
        )
    ) { paddingValues ->
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            Column(
                modifier = modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .fillMaxSize()
                    .padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Thumbnail(
                    href = currentSong?.thumbnailHref.toString(),
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SongInfo(
                        song = currentSong,
                        isLoggedIn = uiState.isLoggedIn,
                        isLiked = uiState.isLiked,
                        isLiking = uiState.isLiking,
                        onToggleLike = playerViewModel::toggleLike,
                        onOpenLyrics = { showLyrics = true },
                        isInPlaylist = isCurrentSongInPlaylist,
                        onOpenPlaylistSheet = {
                            if (isCurrentSongInPlaylist) {
                                showManagementSheet = true
                            } else {
                                showAddToPlaylistSheet = true
                            }
                        }
                    )
                    PlayerControls(
                        isPlaying = uiState.isPlaying,
                        isLoading = uiState.isLoading,
                        progress = uiState.playbackProgress,
                        onSeek = playerViewModel::seek,
                        onSeekPlayer = playerViewModel::seekPlayer,
                        onUpdateSeekBarHeldState = playerViewModel::updateSeekBarHeldState,
                        onOpenQueue = { playerViewModel.setQueueVisibility(true) },
                        onOpenSleepTimer = { playerViewModel.setSleepTimerSheetVisibility(true) },
                        onOpenSpeedSelector = { playerViewModel.setSpeedSelectorVisibility(true) },
                        playbackSpeed = uiState.playbackSpeed,
                        sleepTimerRemainingSeconds = uiState.sleepTimerRemainingSeconds,
                        audioInfo = playbackData,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        onDownload = {
                            if (!isDownloading && !isDownloaded) {
                                val song = currentSong ?: return@PlayerControls
                                scope.launch {
                                    try { downloadRepo.downloadSongStandalone(song) }
                                    catch (_: Exception) {}
                                }
                            }
                        },
                    )
                }
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(
                modifier = modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                ) {
                    Thumbnail(
                        href = currentSong?.thumbnailHref.toString(),
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    SongInfo(
                        song = currentSong,
                        isLoggedIn = uiState.isLoggedIn,
                        isLiked = uiState.isLiked,
                        isLiking = uiState.isLiking,
                        onToggleLike = playerViewModel::toggleLike,
                        onOpenLyrics = { showLyrics = true },
                        isInPlaylist = isCurrentSongInPlaylist,
                        onOpenPlaylistSheet = {
                            if (isCurrentSongInPlaylist) {
                                showManagementSheet = true
                            } else {
                                showAddToPlaylistSheet = true
                            }
                        }
                    )

                    PlayerControls(
                        isPlaying = uiState.isPlaying,
                        isLoading = uiState.isLoading,
                        progress = uiState.playbackProgress,
                        onSeek = playerViewModel::seek,
                        onSeekPlayer = playerViewModel::seekPlayer,
                        onUpdateSeekBarHeldState = playerViewModel::updateSeekBarHeldState,
                        onOpenQueue = { playerViewModel.setQueueVisibility(true) },
                        onOpenSleepTimer = { playerViewModel.setSleepTimerSheetVisibility(true) },
                        onOpenSpeedSelector = { playerViewModel.setSpeedSelectorVisibility(true) },
                        playbackSpeed = uiState.playbackSpeed,
                        sleepTimerRemainingSeconds = uiState.sleepTimerRemainingSeconds,
                        audioInfo = playbackData,
                        isDownloaded = isDownloaded,
                        isDownloading = isDownloading,
                        onDownload = {
                            if (!isDownloading && !isDownloaded) {
                                val song = currentSong ?: return@PlayerControls
                                scope.launch {
                                    try { downloadRepo.downloadSongStandalone(song) }
                                    catch (_: Exception) {}
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Bottom sheets
    if (uiState.isSpeedSelectorShown) {
        SpeedSelectorBottomSheet(
            changeVisibility = playerViewModel::setSpeedSelectorVisibility,
            currentSpeed = uiState.playbackSpeed,
            onSelectSpeed = playerViewModel::setPlaybackSpeed,
        )
    }

    if (uiState.isQueueModalShown) {
        QueueBottomSheet(
            changeVisibility = playerViewModel::setQueueVisibility,
            songs = uiState.queue,
            currentIndex = uiState.currentIndex
        )
    }

    if (uiState.isSleepTimerModalShown) {
        SleepTimerBottomSheet(
            changeVisibility = playerViewModel::setSleepTimerSheetVisibility,
            activeRemainingSeconds = uiState.sleepTimerRemainingSeconds,
            onStartTimer = playerViewModel::startSleepTimer,
            onStartEndOfSong = playerViewModel::startSleepTimerEndOfSong,
            onCancelTimer = playerViewModel::cancelSleepTimer,
        )
    }

    // Playlist management sheet
    if (showManagementSheet && currentSong != null) {
        SongManagementBottomSheet(
            song = currentSong,
            onDismiss = { showManagementSheet = false },
            onAddToAnotherPlaylist = {
                showManagementSheet = false
                showAddToPlaylistSheet = true
            }
        )
    }

    // Add-to-playlist sheet
    if (showAddToPlaylistSheet && currentSong != null) {
        AddToPlaylistBottomSheet(
            song = currentSong,
            memberPlaylistIds = memberIds,
            onDismiss = { showAddToPlaylistSheet = false }
        )
    }

    // Lyrics sheet — shows on top of player sheet
    if (showLyrics) {
        ModalBottomSheet(
            sheetMaxWidth = Dp.Unspecified,
            onDismissRequest = { showLyrics = false },
            sheetState = rememberBottomSheetState(
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                initialValue = SheetValue.Expanded
            )
        ) {
            LyricsScreen(
                onBack = { showLyrics = false },
                application = application
            )
        }
    }
}

@Composable
fun Thumbnail(
    href: String,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier.padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        val size = minOf(maxWidth, maxHeight)

        AnimatedContent(
            targetState = href,
            transitionSpec = {
                fadeIn(
                    animationSpec = tween(Constants.Player.IMAGE_TRANSITION_DELAY)
                ).togetherWith(
                    fadeOut(
                        animationSpec = tween(Constants.Player.IMAGE_TRANSITION_DELAY)
                    )
                )
            }
        ) { targetState ->
            SquareImage(
                uri = targetState,
                modifier = Modifier.size(size)
            )
        }
    }
}

@Composable
fun SongInfo(
    song: Song?,
    isLoggedIn: Boolean = false,
    isLiked: Boolean = false,
    isLiking: Boolean = false,
    onToggleLike: () -> Unit = {},
    onOpenLyrics: () -> Unit = {},
    /** Whether the current song belongs to at least one local playlist. */
    isInPlaylist: Boolean = false,
    /** Called when the playlist icon is tapped. The caller decides which sheet to open. */
    onOpenPlaylistSheet: () -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = song?.title ?: "",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.basicMarquee()
            )
            Text(
                text = song?.artist ?: "",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.basicMarquee()
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Lyrics button
            IconButton(onClick = onOpenLyrics) {
                Icon(
                    imageVector = Icons.Outlined.Lyrics,
                    contentDescription = stringResource(R.string.lyrics),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Playlist button — shows a filled tick when the song is in a playlist,
            // or an outline add icon when it isn't. Tapping opens the appropriate sheet.
            IconButton(onClick = onOpenPlaylistSheet) {
                Icon(
                    imageVector = if (isInPlaylist) Icons.Rounded.LibraryAddCheck
                                  else Icons.Rounded.LibraryAdd,
                    contentDescription = if (isInPlaylist)
                        stringResource(R.string.manage_playlists)
                    else
                        stringResource(R.string.add_to_playlist),
                    tint = if (isInPlaylist) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Like button (only when logged in)
            if (isLoggedIn) {
                FilledIconToggleButton(
                    checked = isLiked,
                    onCheckedChange = {
                        if (isLiking) return@FilledIconToggleButton
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onToggleLike()
                    },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        checkedContainerColor = IconButtonDefaults.filledIconToggleButtonColors().checkedContainerColor,
                        checkedContentColor = IconButtonDefaults.filledIconToggleButtonColors().checkedContentColor,
                    ),
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isLiked) {
                            stringResource(R.string.unlike)
                        } else {
                            stringResource(R.string.like)
                        }
                    )
                }
            }
        }
    }
}
