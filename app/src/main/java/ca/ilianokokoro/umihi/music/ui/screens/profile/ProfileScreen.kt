package ca.ilianokokoro.umihi.music.ui.screens.profile

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
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

    // Re-fetch profile data whenever the screen opens with a logged-in session that has
    // missing data. Covers existing sessions that pre-date profile caching.
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) profileViewModel.refreshProfileIfNeeded()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
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
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                // Hero header (shared by logged-in and logged-out states)
                ProfileHeader(
                    avatarUrl = if (isLoggedIn) accountAvatarUrl else "",
                    displayName = accountName,
                    email = accountEmail,
                    isRefreshing = isRefreshing,
                    isLoggedIn = isLoggedIn
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Glanceable stat cards
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp)
                ) {
                    item {
                        ProfileStatCard(
                            icon = Icons.Outlined.MusicNote,
                            title = "Connected Service",
                            value = "YouTube Music"
                        )
                    }
                    item {
                        ProfileStatCard(
                            icon = Icons.Rounded.Download,
                            title = "Downloads",
                            value = stringResource(R.string.songs, downloadedSongCount)
                        )
                    }
                    item {
                        ProfileStatCard(
                            icon = Icons.Outlined.FolderOpen,
                            title = "Download Location",
                            value = downloadLocation.ifBlank {
                                stringResource(R.string.internal_storage)
                            }
                        )
                    }
                }

                if (isLoggedIn) {
                    Spacer(modifier = Modifier.height(20.dp))

                    // Informational settings-style rows (Material3 ListItem, matching
                    // the visual language used across the app's settings screens).
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column {
                            ListItem(
                                leadingContent = {
                                    Icon(Icons.Outlined.Person, contentDescription = null)
                                },
                                supportingContent = { Text(accountName.ifBlank { "—" }) }
                            ) {
                                Text("Display Name")
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            ListItem(
                                leadingContent = {
                                    Icon(Icons.Outlined.Email, contentDescription = null)
                                },
                                supportingContent = { Text(accountEmail.ifBlank { "—" }) }
                            ) {
                                Text("Email")
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            ListItem(
                                leadingContent = {
                                    Icon(Icons.Outlined.MusicNote, contentDescription = null)
                                },
                                supportingContent = { Text("YouTube Music") }
                            ) {
                                Text("Connected Service")
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null)
                            Text("Sign In", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Logout — pinned below the scrollable content and visually separated
            // from the informational rows above so it doesn't read as an extra row.
            if (isLoggedIn) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            profileViewModel.logOut()
                            onLoggedOut()
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 24.dp,
                            end = 24.dp,
                            top = 16.dp,
                            bottom = 16.dp +
                                WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding() / 2
                        ),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                        Text("Log Out", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileStatCard(
    icon: ImageVector,
    title: String,
    value: String,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .width(150.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
