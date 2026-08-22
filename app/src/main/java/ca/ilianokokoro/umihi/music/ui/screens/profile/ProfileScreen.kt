package ca.ilianokokoro.umihi.music.ui.screens.profile

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import ca.ilianokokoro.umihi.music.ui.components.FadingStatusBarWrapper
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.SettingsItem
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.SettingsSection
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit = {},
    onLogin: () -> Unit = {},
    application: Application
) {
    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModel.Factory(application)
    )

    // All account state flows through the ViewModel — a single source of truth,
    // so the screen can never show stale values while a fetch is updating them.
    val accountName by profileViewModel.accountName.collectAsStateWithLifecycle()
    val accountEmail by profileViewModel.accountEmail.collectAsStateWithLifecycle()
    val accountAvatarUrl by profileViewModel.accountAvatarUrl.collectAsStateWithLifecycle()
    val cookies by profileViewModel.cookies.collectAsStateWithLifecycle()
    val isRefreshing by profileViewModel.isRefreshing.collectAsStateWithLifecycle()
    val downloadedSongCount by profileViewModel.downloadedSongCount.collectAsStateWithLifecycle()
    val downloadLocation by profileViewModel.downloadLocation.collectAsStateWithLifecycle()

    val isLoggedIn = cookies.isNotEmpty()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Re-fetch profile data whenever the screen opens with a logged-in session that has
    // missing data. Covers existing sessions that pre-date profile caching.
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) profileViewModel.refreshProfileIfNeeded()
    }

    // Avatar state — normalize the URL and handle load failures
    val safeAvatarUrl = remember(accountAvatarUrl) {
        UmihiHelper.normalizeGoogleAvatarUrl(accountAvatarUrl)
    }
    var avatarRetry by remember(safeAvatarUrl) { mutableIntStateOf(0) }
    var avatarFailed by remember(safeAvatarUrl) { mutableStateOf(false) }
    // Auto-reset avatarFailed so transient failures recover
    LaunchedEffect(safeAvatarUrl, avatarFailed) {
        if (avatarFailed) {
            kotlinx.coroutines.delay(10_000)
            avatarFailed = false
        }
    }

    FadingStatusBarWrapper { statusBarHeight ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.profile)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                )
            },
            contentWindowInsets = WindowInsets(0.dp)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = statusBarHeight + paddingValues.calculateTopPadding(),
                        bottom = 200.dp + paddingValues.calculateBottomPadding()
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.profile),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (isLoggedIn) {
                    // ── Account Header ────────────────────────────────────────
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Avatar — circular, with fallback icon underneath
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .shadow(4.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = stringResource(R.string.cd_profile_picture),
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                if (safeAvatarUrl.isNotBlank() && !avatarFailed) {
                                    val avatarRequest = remember(safeAvatarUrl, avatarRetry) {
                                        ImageRequest.Builder(context)
                                            .data(safeAvatarUrl)
                                            .size(192, 192)
                                            // Always bypass disk cache for avatars. A failed
                                            // fetch (403, timeout, CDN glitch) must not latch
                                            // in the disk cache and block subsequent retries.
                                            .diskCachePolicy(CachePolicy.DISABLED)
                                            .apply {
                                                if (avatarRetry > 0) {
                                                    // On retry also bypass memory cache so
                                                    // Coil re-fetches from network.
                                                    @Suppress("UNCHECKED_CAST")
                                                    memoryCacheKey(null as String?)
                                                }
                                            }
                                            .build()
                                    }
                                    AsyncImage(
                                        model = avatarRequest,
                                        contentDescription = stringResource(R.string.cd_profile_picture),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        onError = {
                                            ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe(
                                                "ProfileScreen: avatar load failed for $safeAvatarUrl"
                                            )
                                            if (avatarRetry == 0) {
                                                avatarRetry = 1
                                            } else {
                                                avatarFailed = true
                                            }
                                        }
                                    )
                                }
                            }

                            Text(
                                text = accountName.ifBlank { "YouTube Account" },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (accountEmail.isNotBlank()) {
                                Text(
                                    text = accountEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ── Account Details ──────────────────────────────────────
                    SettingsSection(title = stringResource(R.string.account)) {
                        SettingsItem(
                            title = stringResource(R.string.profile_display_name),
                            subtitle = accountName.ifBlank { "—" },
                            leadingIcon = Icons.Outlined.Person,
                            onClick = {}
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SettingsItem(
                            title = stringResource(R.string.profile_email),
                            subtitle = accountEmail.ifBlank { "—" },
                            leadingIcon = Icons.Outlined.Email,
                            onClick = {}
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        SettingsItem(
                            title = stringResource(R.string.profile_connected_service),
                            subtitle = stringResource(R.string.profile_youtube_music),
                            leadingIcon = Icons.Outlined.MusicNote,
                            onClick = {}
                        )
                    }

                    // ── Downloads ─────────────────────────────────────────────
                    SettingsSection(title = stringResource(R.string.data_and_storage)) {
                        SettingsItem(
                            title = stringResource(R.string.downloaded_playlist_title),
                            subtitle = stringResource(R.string.songs, downloadedSongCount),
                            leadingIcon = Icons.Outlined.FolderOpen,
                            onClick = {}
                        )
                    }
                } else {
                    // ── Logged Out State ──────────────────────────────────────
                    Spacer(modifier = Modifier.height(32.dp))

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = "Profile picture",
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.profile_not_logged_in),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.profile_sign_in_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null)
                            Text(stringResource(R.string.profile_sign_in), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                // ── Logout ────────────────────────────────────────────────────
                if (isLoggedIn) {
                    SettingsSection(title = "") {
                        SettingsItem(
                            title = stringResource(R.string.log_out),
                            subtitle = stringResource(R.string.profile_sign_out_description),
                            leadingIcon = Icons.AutoMirrored.Outlined.Logout,
                            onClick = {
                                scope.launch {
                                    profileViewModel.logOut()
                                    onLoggedOut()
                                    onBack()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
