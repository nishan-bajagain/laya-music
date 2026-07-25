@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package ca.ilianokokoro.umihi.music.ui.screens.lyrics

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.models.LyricLine
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    onBack: () -> Unit,
    application: Application,
    viewModel: LyricsViewModel = viewModel(factory = LyricsViewModel.Factory(application))
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Trigger lyrics loading immediately when the sheet opens
    LaunchedEffect(Unit) {
        viewModel.loadLyricsForCurrentSong()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
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

                LyricsScreenState.Offline -> {
                    EmptyStateContent(
                        icon = { Icon(Icons.Outlined.WifiOff, contentDescription = null, modifier = Modifier.size(48.dp).alpha(0.5f), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        message = stringResource(R.string.lyrics_offline),
                        onRetry = viewModel::retry
                    )
                }

                is LyricsScreenState.Error -> {
                    EmptyStateContent(
                        icon = null,
                        message = stringResource(R.string.lyrics_error),
                        onRetry = if (state.retryable) viewModel::retry else null
                    )
                }

                is LyricsScreenState.Synced -> {
                    SyncedLyricsContent(
                        lines = state.lines,
                        currentIndex = state.currentIndex,
                        autoScrollEnabled = uiState.autoScrollEnabled,
                        offsetMs = uiState.offsetMs,
                        onUserScrolled = viewModel::onUserScrolled,
                        onLineTapped = { viewModel.resumeAutoScroll() },
                        onOffsetAdjust = viewModel::adjustOffset,
                        onOffsetReset = viewModel::resetOffset
                    )
                }

                is LyricsScreenState.Plain -> {
                    PlainLyricsContent(lines = state.lines)
                }
            }
        }
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
    autoScrollEnabled: Boolean,
    offsetMs: Long,
    onUserScrolled: () -> Unit,
    onLineTapped: (Int) -> Unit,
    onOffsetAdjust: (Long) -> Unit,
    onOffsetReset: () -> Unit
) {
    val listState = rememberLazyListState()

    // Auto-scroll: keep the active line vertically centred in the viewport.
    LaunchedEffect(currentIndex, autoScrollEnabled) {
        if (!autoScrollEnabled || currentIndex < 0 || lines.isEmpty()) return@LaunchedEffect

        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportSize.height

        if (viewportHeight == 0) {
            listState.animateScrollToItem(index = currentIndex)
            return@LaunchedEffect
        }

        val viewportCenter = viewportHeight / 2
        val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }

        if (visibleItem != null) {
            val itemCenter = visibleItem.offset + visibleItem.size / 2
            val delta = (itemCenter - viewportCenter).toFloat()
            if (kotlin.math.abs(delta) > 24f) {
                listState.animateScrollBy(delta)
            }
        } else {
            listState.animateScrollToItem(
                index = currentIndex,
                scrollOffset = -(viewportCenter / 2)
            )
        }
    }

    // Detect manual scroll to pause auto-scroll
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (scrolling) onUserScrolled() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 24.dp,
                vertical = 120.dp
            )
        ) {
            itemsIndexed(lines, key = { idx, _ -> idx }) { index, line ->
                val isActive = currentIndex >= 0 && index == currentIndex

                // Alpha: active = fully opaque, inactive = 35%
                val alpha by animateFloatAsState(
                    targetValue = if (isActive) 1f else 0.35f,
                    animationSpec = tween(durationMillis = 200),
                    label = "lyric_alpha_$index"
                )

                // Scale: active = slightly larger (Spotify-style)
                val scale by animateFloatAsState(
                    targetValue = if (isActive) 1.05f else 1f,
                    animationSpec = tween(durationMillis = 200),
                    label = "lyric_scale_$index"
                )

                // Color: active = primary, inactive = onSurface
                val color by animateColorAsState(
                    targetValue = if (isActive)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface,
                    animationSpec = tween(durationMillis = 200),
                    label = "lyric_color_$index"
                )

                Text(
                    text = line.text,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = color
                    ),
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onLineTapped(index) }
                        .padding(vertical = 6.dp)
                )
            }
        }

        // Offset controls — fixed at the bottom of the lyrics panel
        OffsetControls(
            offsetMs = offsetMs,
            onAdjust = onOffsetAdjust,
            onReset = onOffsetReset,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

// ── Offset controls ───────────────────────────────────────────────────────────

@Composable
private fun OffsetControls(
    offsetMs: Long,
    onAdjust: (Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = { onAdjust(-100L) },
            modifier = Modifier.size(36.dp)
        ) {
            Text(
                text = "−",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        // Offset label — tappable to reset to zero
        Text(
            text = when {
                offsetMs == 0L -> stringResource(R.string.lyrics_offset_zero)
                offsetMs > 0  -> "+${offsetMs}ms"
                else          -> "${offsetMs}ms"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onReset() }
                .padding(horizontal = 4.dp)
        )

        FilledTonalIconButton(
            onClick = { onAdjust(+100L) },
            modifier = Modifier.size(36.dp)
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

// ── Plain lyrics ──────────────────────────────────────────────────────────────

@Composable
private fun PlainLyricsContent(lines: List<LyricLine>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp,
            vertical = 24.dp
        )
    ) {
        itemsIndexed(lines, key = { idx, _ -> idx }) { _, line ->
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
