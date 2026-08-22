@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ca.ilianokokoro.umihi.music.ui.screens.home

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.helpers.ConnectivityHelper
import ca.ilianokokoro.umihi.music.core.managers.PlayerManager
import ca.ilianokokoro.umihi.music.models.Song
import ca.ilianokokoro.umihi.music.ui.components.FadingStatusBarWrapper
import ca.ilianokokoro.umihi.music.ui.components.SquareImage
import ca.ilianokokoro.umihi.music.ui.navigation.viewmodels.SharedViewModel
import kotlinx.coroutines.delay
import java.util.Calendar

private const val HERO_AUTO_SCROLL_MS = 4_500L

@Composable
fun HomeScreen(
    sharedViewModel: SharedViewModel,
    onSettingsButtonPress: () -> Unit,
    onProfilePress: () -> Unit = {},
    onLogin: () -> Unit = {},
    onPlaylistPressed: (playlistInfo: ca.ilianokokoro.umihi.music.models.PlaylistInfo) -> Unit,
    application: Application,
    homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(application = application)
    )
) {
    val uiState = homeViewModel.uiState.collectAsStateWithLifecycle().value

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isOnline by remember { mutableStateOf(ConnectivityHelper.isNetworkAvailable(context)) }

    // Re-check connectivity when app returns to foreground
    LaunchedEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    val nowOnline = ConnectivityHelper.isNetworkAvailable(context)
                    if (nowOnline != isOnline) {
                        isOnline = nowOnline
                        homeViewModel.loadHomeFeed()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        try {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { }
        } finally {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Time-based greeting
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    FadingStatusBarWrapper { statusBarHeight ->
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp),
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = statusBarHeight + paddingValues.calculateTopPadding() + 8.dp,
                    bottom = Constants.Ui.SCROLLABLE_BOTTOM_PADDING
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // ── Greeting header + profile icon ──────────────────────
                item(key = "home_greeting") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isOnline) greeting else stringResource(R.string.home),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onProfilePress) {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = stringResource(R.string.cd_profile),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // ── Offline banner ────────────────────────────────────────
                if (!isOnline) {
                    item(key = "home_offline_banner") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.home_offline_mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // ── Hero carousel (only when >=2 distinct picks) ────────
                if (uiState.heroPicks.size >= 2) {
                    item(key = "home_hero_carousel") {
                        HeroCarousel(
                            picks = uiState.heroPicks,
                            onSongPressed = { song ->
                                val index = uiState.heroPicks.indexOfFirst { it.youtubeId == song.youtubeId }
                                    .coerceAtLeast(0)
                                PlayerManager.playQueue(
                                    uiState.heroPicks.map { it.mediaItem },
                                    index
                                )
                            }
                        )
                    }
                }

                // ── Named shelves (stable section order, deduplicated) ───
                if (uiState.shelves.isNotEmpty()) {
                    items(
                        items = uiState.shelves,
                        key = { "shelf_${it.title}" },
                        contentType = { _ -> "shelf" }
                    ) { shelf ->
                        HomeShelfRail(
                            title = shelf.title,
                            songs = shelf.songs,
                            onSongPressed = { song ->
                                val index = shelf.songs.indexOfFirst { it.youtubeId == song.youtubeId }
                                    .coerceAtLeast(0)
                                PlayerManager.playQueue(
                                    shelf.songs.map { it.mediaItem },
                                    index
                                )
                            }
                        )
                    }
                }

                // ── Skeleton loading (only while fetching, not on error) ──
                if (uiState.shelvesLoading && !uiState.shelvesError && uiState.shelves.isEmpty()) {
                    items(4, key = { "skeleton_$it" }, contentType = { _ -> "skeleton" }) {
                        HomeShelfSkeleton()
                    }
                }

                // ── Error state with retry ────────────────────────────────
                if (uiState.shelvesError && uiState.shelves.isEmpty() && isOnline) {
                    item(key = "home_error", contentType = "error") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Couldn't load recommendations",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Check your connection and try again",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = { homeViewModel.loadHomeFeed() }) {
                                    Text(stringResource(R.string.retry))
                                }
                            }
                        }
                    }
                }

                // ── Empty offline state ───────────────────────────────────
                if (!isOnline && uiState.shelves.isEmpty() && !uiState.shelvesLoading) {
                    item(key = "home_offline_empty", contentType = "empty") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Downloading,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = stringResource(R.string.home_offline_no_downloads),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hero carousel — auto-sliding, swipeable, with dot indicators
// Only renders when >=2 items are provided (caller ensures this)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroCarousel(
    picks: List<Song>,
    onSongPressed: (Song) -> Unit,
) {
    if (picks.size < 2) return

    val pagerState = rememberPagerState(pageCount = { picks.size })
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Auto-advance: advance page every [HERO_AUTO_SCROLL_MS]
    LaunchedEffect(pagerState, autoScrollEnabled) {
        if (!autoScrollEnabled) return@LaunchedEffect
        while (true) {
            delay(HERO_AUTO_SCROLL_MS)
            val nextPage = (pagerState.currentPage + 1) % picks.size
            pagerState.animateScrollToPage(nextPage)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section title
        Text(
            text = stringResource(R.string.home_for_you),
            style = MaterialTheme.typography.titleLarge,
        )

        // Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentPadding = PaddingValues(horizontal = 40.dp),
            pageSpacing = 12.dp,
        ) { page ->
            val song = picks[page]
            // Resume auto-scroll once settled
            LaunchedEffect(pagerState.settledPage) {
                autoScrollEnabled = true
            }

            Card(
                onClick = { onSongPressed(song) },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Full-bleed artwork
                    SquareImage(
                        localPath = song.thumbnailPath,
                        remoteUrl = song.thumbnailHref,
                        fallbackUrl = song.thumbnailFallbackUrl,
                        contentDescription = null,
                        cornerRadius = 0.dp,
                        requestSize = 512,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Gradient scrim at bottom
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                                )
                            )
                    )

                    // Song info overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Play button
                    FilledIconButton(
                        onClick = { onSongPressed(song) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.play),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        // Dot indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(picks.size) { index ->
                val isSelected = pagerState.currentPage == index
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .then(
                            if (isSelected) Modifier.size(width = 20.dp, height = 6.dp)
                            else Modifier.size(6.dp)
                        ),
                    shape = RoundedCornerShape(3.dp),
                    color = if (isSelected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                ) {}
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shelf rail — titled horizontal row of song cards
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HomeShelfRail(
    title: String,
    songs: List<Song>,
    onSongPressed: (Song) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(
                items = songs,
                key = { it.youtubeId },
                contentType = { _ -> "song_card" }
            ) { song ->
                RecommendationCard(
                    song = song,
                    onClick = { onSongPressed(song) }
                )
            }
        }
    }
}

@Composable
private fun HomeShelfSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .width(140.dp)
                .height(24.dp)
        ) {}
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(4) { RecommendationSkeletonCard() }
        }
    }
}

@Composable
private fun RecommendationCard(
    song: Song,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(152.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        SquareImage(
            localPath = song.thumbnailPath,
            remoteUrl = song.thumbnailHref,
            fallbackUrl = song.thumbnailFallbackUrl,
            contentDescription = song.title,
            cornerRadius = 0.dp,
            requestSize = 256,
            modifier = Modifier
                .fillMaxWidth()
                .height(152.dp)
        )
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RecommendationSkeletonCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.width(152.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
            ) {}
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
            ) {}
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .padding(start = 10.dp, end = 28.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .height(11.dp)
            ) {}
        }
    }
}
