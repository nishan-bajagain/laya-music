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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.AnnotatedString
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
import ca.ilianokokoro.umihi.music.models.LyricWord
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
import kotlinx.coroutines.flow.collectLatest
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

    // Stable function references so the lyrics list can skip recomposition
    // when only positionMs changes — bound references are recreated on every
    // composition otherwise, which would defeat the derived-state isolation
    // in SyncedLyricsContent.
    val onUserScrolled = remember(viewModel) { viewModel::onUserScrolled }
    val onLineTapped = remember(viewModel) { viewModel::seekToLyric }
    val onJumpToCurrent = remember(viewModel) { viewModel::resumeAutoScroll }

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
                    // uiState is re-emitted every 100 ms position tick, but the
                    // list only depends on the current line index. Slice the
                    // state into two derived States whose references are stable:
                    // the LazyColumn subtree is never recomposed by a position
                    // tick, and only the active lyric row reads positionMs.
                    val currentIndexState = remember {
                        derivedStateOf {
                            (uiState.screenState as? LyricsScreenState.Synced)
                                ?.currentIndex ?: -1
                        }
                    }
                    val positionMsState = remember { derivedStateOf { uiState.positionMs } }
                    SyncedLyricsContent(
                        lines = state.lines,
                        currentIndex = currentIndexState,
                        positionMs = positionMsState,
                        autoScrollEnabled = uiState.autoScrollEnabled,
                        onUserScrolled = onUserScrolled,
                        onLineTapped = onLineTapped,
                        onJumpToCurrent = onJumpToCurrent,
                        showControls = true
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
    currentIndex: State<Int>,
    positionMs: State<Long>,
    autoScrollEnabled: Boolean,
    onUserScrolled: () -> Unit,
    onLineTapped: (Int) -> Unit,
    onJumpToCurrent: () -> Unit,
    showControls: Boolean
) {
    val listState = rememberLazyListState()
    var programmaticScroll by remember { mutableStateOf(false) }

    // Auto-scroll: keep the active line vertically centred in the actual
    // viewport, including the LazyColumn's content padding. The extra pass
    // after a jump is important because the target item's measured height is
    // needed for exact centering.
    //
    // Keyed on the State objects (stable references) rather than the index
    // value, so index changes are consumed inside via snapshotFlow and this
    // scope — and therefore the LazyColumn — is never recomposed on a plain
    // position tick. collectLatest cancels any in-flight scroll animation when
    // a new index arrives (rapid seeking/scrubbing) instead of stacking
    // animations or letting the previous one finish first.
    LaunchedEffect(currentIndex, autoScrollEnabled, lines) {
        snapshotFlow { currentIndex.value to autoScrollEnabled }
            .distinctUntilChanged()
            .collectLatest { (idx, autoScroll) ->
                if (!autoScroll || idx < 0 || lines.isEmpty()) return@collectLatest

                programmaticScroll = true
                try {
                    repeat(3) {
                        val layoutInfo = listState.layoutInfo
                        val viewportStart = layoutInfo.viewportStartOffset
                        val viewportEnd = layoutInfo.viewportEndOffset
                        if (viewportEnd <= viewportStart) return@repeat

                        val viewportCenter = (viewportStart + viewportEnd) / 2f
                        val visibleItem =
                            layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }

                        if (visibleItem == null) {
                            // Bring a distant line into the viewport first. The next
                            // pass uses its real measured height to center it exactly.
                            listState.animateScrollToItem(index = idx)
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

    // The "jump to current" button only depends on the auto-scroll flag and
    // whether an active line exists — derived so the list scope does not
    // recompose on every index change.
    val jumpVisible by remember(currentIndex, autoScrollEnabled) {
        derivedStateOf { !autoScrollEnabled && currentIndex.value >= 0 }
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
                // Per-item derived state: only rows whose active flag actually
                // flips are invalidated when the current line changes, instead
                // of recomposing every composed row on every index change.
                val isActive by remember {
                    derivedStateOf { index == currentIndex.value }
                }
                val onLineTap = remember { { onLineTapped(index) } }
                LyricLineRow(
                    text = line.text,
                    words = line.words,
                    isActive = isActive,
                    positionMs = positionMs,
                    onClick = onLineTap
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
        if (jumpVisible) {
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

/**
 * One lyric row, extracted into its own composable so Compose can skip rows
 * that did not actually change. Parameters are stable references/values:
 * [positionMs] is a [State] that only the *active* row reads (for karaoke word
 * timing), so the other ~10 visible rows receive no per-tick invalidation at
 * all — a 100 ms position tick recomposes exactly one row.
 */
@Composable
private fun LyricLineRow(
    text: String,
    words: List<LyricWord>?,
    isActive: Boolean,
    positionMs: State<Long>,
    onClick: () -> Unit
) {
    // Keep inactive lines readable in both light and dark themes,
    // while giving the active line the same strong contrast as the
    // rest of the app's primary actions.
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.42f,
        animationSpec = tween(durationMillis = 250)
    )

    // Scale: active = slightly larger (Spotify-style), spring for smoothness
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.06f else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        )
    )

    // Color: use the active app color scheme rather than a hard-coded palette.
    val color by animateColorAsState(
        targetValue = if (isActive)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 250)
    )

    // The karaoke string is rebuilt only when the currently-spoken word index
    // actually changes (or an active/inactive transition is in flight) — never
    // on every position tick. positionMs.value is read only in this branch, so
    // inactive rows never subscribe to the 100 ms tick.
    val renderedText = if (isActive && !words.isNullOrEmpty()) {
        val activeWordIndex = findActiveWordIndex(words, positionMs.value)
        val activeColor = MaterialTheme.colorScheme.primary
        remember(words, activeWordIndex, activeColor, color) {
            buildAnnotatedString {
                words.forEachIndexed { wordIndex, word ->
                    // Matches the original per-word predicate for contiguous
                    // timed words: the word under the playhead stays lit, words
                    // with no end timestamp stay lit once spoken, ended words
                    // revert to the line's base color.
                    val wordActive = wordIndex == activeWordIndex ||
                        (wordIndex < activeWordIndex && word.endMs == null)
                    withStyle(
                        SpanStyle(
                            color = if (wordActive) activeColor else color
                        )
                    ) {
                        append(word.text)
                    }
                }
            }
        }
    } else {
        remember(text) { AnnotatedString(text) }
    }

    Text(
        text = renderedText,
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
            ) { onClick() }
            .padding(vertical = 6.dp)
    )
}

/**
 * Index of the last word active at [positionMs] (the word under the playhead),
 * or -1 when the position falls between words. Linear scan is fine here — it
 * runs once per tick on a single line of ~5-20 words; the expensive part, the
 * AnnotatedString rebuild, is gated on this index changing.
 */
private fun findActiveWordIndex(words: List<LyricWord>, positionMs: Long): Int {
    var active = -1
    words.forEachIndexed { index, word ->
        if (positionMs >= word.startMs && (word.endMs == null || positionMs < word.endMs)) {
            active = index
        }
    }
    return active
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