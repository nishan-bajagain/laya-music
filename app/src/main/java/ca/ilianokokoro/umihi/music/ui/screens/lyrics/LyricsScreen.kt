@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package ca.ilianokokoro.umihi.music.ui.screens.lyrics

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.models.LyricLine
import ca.ilianokokoro.umihi.music.ui.screens.player.PlayerViewModel
import ca.ilianokokoro.umihi.music.ui.screens.player.components.PlayerControls
import ca.ilianokokoro.umihi.music.ui.screens.player.components.QueueBottomSheet
import ca.ilianokokoro.umihi.music.ui.screens.player.components.SleepTimerBottomSheet
import ca.ilianokokoro.umihi.music.ui.screens.player.components.SpeedSelectorBottomSheet
import ca.ilianokokoro.umihi.music.core.managers.PlaylistMembership
import ca.ilianokokoro.umihi.music.data.database.AppDatabase
import ca.ilianokokoro.umihi.music.data.repositories.DownloadRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.res.Configuration
import androidx.work.WorkInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    onBack: () -> Unit,
    application: Application,
    viewModel: LyricsViewModel = viewModel(factory = LyricsViewModel.Factory(application))
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerViewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(application)
    )
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val playbackProgress by playerViewModel.playbackProgress.collectAsStateWithLifecycle()
    val playbackAudioInfo by PlayerManager.audioInfo.collectAsStateWithLifecycle()
    val currentSong = playerUiState.queue.getOrNull(playerUiState.currentIndex)
    val downloadRepository = remember { DownloadRepository(application) }
    val downloadWorkInfos by remember(currentSong?.youtubeId) {
        currentSong?.youtubeId?.let(downloadRepository::getStandaloneWorkInfoFlow)
            ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedSong by remember(currentSong?.youtubeId) {
        currentSong?.youtubeId?.let {
            AppDatabase.getInstance(application).songRepository().observeDownloadedSong(it)
        } ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val isDownloading = downloadWorkInfos.any {
        it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
    }
    val isDownloaded = downloadedSong != null
    val scope = rememberCoroutineScope()
    val orientation = LocalConfiguration.current.orientation

    // The player controller and queue can become available one frame after
    // this sheet is composed. Key the load to the actual track instead of
    // firing once and silently returning while currentSong is still null.
    LaunchedEffect(currentSong?.youtubeId) {
        if (currentSong != null) {
            viewModel.loadLyricsForSong(currentSong)
        } else {
            // The lyrics sheet can be composed before the MediaController
            // connection has published its queue. Retry briefly so the first
            // open cannot miss the only load trigger.
            repeat(20) {
                viewModel.loadLyricsForCurrentSong()
                delay(250L)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lyrics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                when (val state = uiState.screenState) {
                LyricsScreenState.LoadingCache -> {
                    LoadingContent(message = stringResource(R.string.lyrics_loading_cache))
                }

                LyricsScreenState.LoadingSynced -> {
                    LoadingContent(message = stringResource(R.string.lyrics_loading_synced))
                }

                LyricsScreenState.NotFound -> {
                    EmptyStateContent(
                        icon = { Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp).alpha(0.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        message = stringResource(R.string.lyrics_not_found),
                        onRetry = viewModel::retry
                    )
                }

                LyricsScreenState.Instrumental -> {
                    EmptyStateContent(
                        icon = { Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(48.dp).alpha(0.4f), tint = MaterialTheme.colorScheme.primary) },
                        message = stringResource(R.string.lyrics_instrumental),
                        retryLabel = null
                    )
                }

                is LyricsScreenState.NetworkError -> {
                    EmptyStateContent(
                        icon = { Icon(Icons.Outlined.WifiOff, contentDescription = null, modifier = Modifier.size(48.dp).alpha(0.5f), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        message = state.message,
                        onRetry = viewModel::retry
                    )
                }

                LyricsScreenState.RateLimited -> {
                    EmptyStateContent(
                        icon = null,
                        message = stringResource(R.string.lyrics_rate_limited),
                        onRetry = viewModel::retry
                    )
                }

                is LyricsScreenState.Unknown -> {
                    EmptyStateContent(
                        icon = null,
                        message = state.message,
                        onRetry = viewModel::retry
                    )
                }

                is LyricsScreenState.Error -> {
                    EmptyStateContent(
                        icon = null,
                        message = state.message.ifBlank { stringResource(R.string.lyrics_error) },
                        onRetry = if (state.retryable) viewModel::retry else null
                    )
                }

                is LyricsScreenState.Synced -> {
                    SyncedLyricsContent(
                        lines = state.lines,
                        currentIndex = state.currentIndex,
                        positionMs = uiState.positionMs,
                        autoScrollEnabled = uiState.autoScrollEnabled,
                        onUserScrolled = viewModel::onUserScrolled,
                        onLineTapped = viewModel::seekToLyric,
                        onJumpToCurrent = viewModel::resumeAutoScroll,
                        showControls = true,
                        provider = state.provider
                    )
                }

                is LyricsScreenState.Plain -> {
                    if (state.lines.isEmpty()) {
                        EmptyStateContent(
                            icon = {
                                Icon(
                                    Icons.Outlined.MusicNote,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).alpha(0.4f),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            message = stringResource(R.string.lyrics_not_found),
                            onRetry = viewModel::retry
                        )
                    } else {
                        PlainLyricsContent(
                            lines = state.lines,
                            onRetry = viewModel::retry
                        )
                    }
                }
                }
            }

            // Keep transport controls visible regardless of whether the
            // provider returned synced, plain, loading, or empty lyrics.
            LyricsPlayerControls(
                playerViewModel = playerViewModel,
                playerUiState = playerUiState,
                playbackProgress = playbackProgress,
                playbackAudioInfo = playbackAudioInfo,
                isDownloaded = isDownloaded,
                isDownloading = isDownloading,
                onDownload = {
                    currentSong?.let { song ->
                        scope.launch {
                            downloadRepository.downloadSongStandalone(song)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    if (playerUiState.isSpeedSelectorShown) {
        SpeedSelectorBottomSheet(
            changeVisibility = playerViewModel::setSpeedSelectorVisibility,
            currentSpeed = playerUiState.playbackSpeed,
            onSelectSpeed = playerViewModel::setPlaybackSpeed
        )
    }

    if (playerUiState.isQueueModalShown) {
        QueueBottomSheet(
            changeVisibility = playerViewModel::setQueueVisibility,
            songs = playerUiState.queue,
            currentIndex = playerUiState.currentIndex
        )
    }

    if (playerUiState.isSleepTimerModalShown) {
        SleepTimerBottomSheet(
            changeVisibility = playerViewModel::setSleepTimerSheetVisibility,
            activeRemainingSeconds = playerUiState.sleepTimerRemainingSeconds,
            onStartTimer = playerViewModel::startSleepTimer,
            onStartEndOfSong = playerViewModel::startSleepTimerEndOfSong,
            onCancelTimer = playerViewModel::cancelSleepTimer
        )
    }
}

// ── Loading ───────────────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(message: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── Synced lyrics ─────────────────────────────────────────────────────────────

@Composable
private fun SyncedLyricsContent(
    lines: List<LyricLine>,
    currentIndex: Int,
    positionMs: Long,
    autoScrollEnabled: Boolean,
    onUserScrolled: () -> Unit,
    onLineTapped: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
    showControls: Boolean,
    provider: String
) {
    val listState = rememberLazyListState()
    var programmaticScroll by remember { mutableStateOf(false) }

    // Auto-scroll: keep the active line vertically centred in the actual
    // viewport, including the LazyColumn's content padding. The extra pass
    // after a jump is important because the target item's measured height is
    // needed for exact centering.
    LaunchedEffect(currentIndex, autoScrollEnabled) {
        if (!autoScrollEnabled || currentIndex < 0 || lines.isEmpty()) return@LaunchedEffect

        programmaticScroll = true
        try {
            repeat(3) {
                val layoutInfo = listState.layoutInfo
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset
                if (viewportEnd <= viewportStart) return@repeat

                val viewportCenter = (viewportStart + viewportEnd) / 2f
                val visibleItem =
                    layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }

                if (visibleItem == null) {
                    // Bring a distant line into the viewport first. The next
                    // pass uses its real measured height to center it exactly.
                    listState.animateScrollToItem(index = currentIndex)
                    return@repeat
                }

                val itemCenter = visibleItem.offset + visibleItem.size / 2f
                val delta = itemCenter - viewportCenter
                if (kotlin.math.abs(delta) > 12f) {
                    // A slightly damped spring feels like Spotify's gentle
                    // follow motion instead of a list that snaps on each line.
                    listState.animateScrollBy(
                        delta,
                        animationSpec = spring(
                            dampingRatio = 0.88f,
                            stiffness = 340f
                        )
                    )
                } else {
                    return@repeat
                }
            }
        } finally {
            programmaticScroll = false
        }
    }

    // Programmatic centering also reports scroll progress. Ignore it and pause
    // auto-follow only for scroll activity initiated by the user.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !programmaticScroll) onUserScrolled()
            }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 24.dp,
                vertical = 112.dp
            )
        ) {
            // Include the source index so duplicate timestamps remain valid
            // stable keys instead of colliding and corrupting item state.
            itemsIndexed(lines, key = { idx, line -> idx to line.timeMs }) { index, line ->
                val isActive = currentIndex >= 0 && index == currentIndex

                // Keep inactive lines readable in both light and dark themes,
                // while giving the active line the same strong contrast as the
                // rest of the app's primary actions.
                val alpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.42f,
                    animationSpec = tween(durationMillis = 250),
                    label = "lyric_alpha_$index"
                )

                // Scale: active = slightly larger (Spotify-style), spring for smoothness
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.06f else 1f,
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = 300f
                    ),
                    label = "lyric_scale_$index"
                )

                // Color: use the active app color scheme rather than a
                // hard-coded Spotify palette.
                val color by animateColorAsState(
                    targetValue = if (isActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 250),
                    label = "lyric_color_$index"
                )

                Text(
                    text = buildAnnotatedString {
                        val words = line.words
                        if (!isActive || words.isNullOrEmpty()) {
                            append(line.text)
                        } else {
                            words.forEach { word ->
                                val wordActive = positionMs >= word.startMs &&
                                    (word.endMs == null || positionMs < word.endMs)
                                withStyle(
                                    SpanStyle(
                                        color = if (wordActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            color
                                        }
                                    )
                                ) {
                                    append(word.text)
                                }
                            }
                        }
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = color,
                        lineHeight = MaterialTheme.typography.headlineSmall.lineHeight * 1.15f
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isActive) {
                                Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 14.dp)
                            } else {
                                Modifier
                            }
                        )
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onLineTapped(index) }
                        .padding(vertical = 6.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(50)
                        )
                )
                Text(
                    text = stringResource(R.string.lyrics_synced),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // "Jump to Current Lyric" button — shown only when the user has scrolled
        // away from the active line (auto-scroll disabled).
        if (!autoScrollEnabled && currentIndex >= 0) {
            FilledTonalButton(
                onClick = onJumpToCurrent,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 120.dp else 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VerticalAlignCenter,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.lyrics_jump_to_current))
            }
        }

    }
}

// ── Playback controls inside the lyrics screen ────────────────────────────────

@Composable
private fun LyricsPlayerControls(
    playerViewModel: PlayerViewModel,
    playerUiState: ca.ilianokokoro.umihi.music.ui.screens.player.PlayerState,
    playbackProgress: ca.ilianokokoro.umihi.music.ui.screens.player.PlaybackProgress,
    playbackAudioInfo: ca.ilianokokoro.umihi.music.models.PlaybackAudioInfo,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlayerControls(
        modifier = modifier,
        isPlaying = playerUiState.isPlaying,
        isLoading = playerUiState.isLoading,
        progress = playbackProgress,
        onSeekPlayer = playerViewModel::seekPlayer,
        onUpdateSeekBarHeldState = playerViewModel::updateSeekBarHeldState,
        onSeek = playerViewModel::seek,
        onOpenQueue = { playerViewModel.setQueueVisibility(true) },
        onOpenSleepTimer = { playerViewModel.setSleepTimerSheetVisibility(true) },
        onOpenSpeedSelector = { playerViewModel.setSpeedSelectorVisibility(true) },
        playbackSpeed = playerUiState.playbackSpeed,
        sleepTimerRemainingSeconds = playerUiState.sleepTimerRemainingSeconds,
        audioInfo = playbackAudioInfo,
        isDownloaded = isDownloaded,
        isDownloading = isDownloading,
        onDownload = onDownload,
        compact = true
    )
}

// ── Plain lyrics ──────────────────────────────────────────────────────────────

@Composable
private fun PlainLyricsContent(
    lines: List<LyricLine>,
    onRetry: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                top = 24.dp,
                end = 24.dp,
                bottom = 136.dp
            )
        ) {
            itemsIndexed(lines, key = { idx, line -> idx to line.timeMs }) { _, line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )
            }
        }

        FilledTonalButton(
            onClick = onRetry,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            Text(stringResource(R.string.lyrics_retry))
        }
    }
}

// ── Empty / error states ──────────────────────────────────────────────────────

@Composable
private fun EmptyStateContent(
    icon: (@Composable () -> Unit)?,
    message: String,
    retryLabel: String? = stringResource(R.string.lyrics_retry),
    onRetry: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(24.dp)
    ) {
        icon?.invoke()
        if (icon != null) Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (onRetry != null && retryLabel != null) {
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(
                onClick = onRetry,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(retryLabel)
            }
        }
    }
}