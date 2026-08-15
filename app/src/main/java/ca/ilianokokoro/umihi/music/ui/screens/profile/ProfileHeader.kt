package ca.ilianokokoro.umihi.music.ui.screens.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ca.ilianokokoro.umihi.music.core.helpers.UmihiHelper
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import coil3.request.ImageRequest

/**
 * Hero header for the profile screen: a gradient band with a large circular
 * avatar (with a subtle shadow), the display name and email — or pulsing
 * skeleton placeholders while profile data is being fetched. Shared by the
 * logged-in and logged-out states so both feel like the same screen.
 */
@Composable
fun ProfileHeader(
    avatarUrl: String,
    displayName: String,
    email: String,
    isRefreshing: Boolean,
    isLoggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Google's accountPhoto can be a protocol-relative 96px thumbnail —
    // normalize before Coil sees it so the avatar loads and isn't pixelated.
    val safeAvatarUrl = remember(avatarUrl) { UmihiHelper.normalizeGoogleAvatarUrl(avatarUrl) }
    var avatarRetry by remember(safeAvatarUrl) { mutableIntStateOf(0) }
    // A failed load must not kill the avatar for the rest of the session: a
    // single transient failure (cold network, CDN hiccup, decode glitch) used
    // to latch the placeholder forever. Now the failed state auto-resets after
    // a short delay, so Coil retries periodically until the image actually
    // loads — while a genuinely dead URL still degrades to the placeholder
    // between attempts instead of spamming requests.
    var avatarFailed by remember(safeAvatarUrl) { mutableStateOf(false) }
    LaunchedEffect(safeAvatarUrl, avatarFailed) {
        if (avatarFailed) {
            delay(10_000)
            avatarFailed = false
        }
    }

    // Skeleton while a fetch is in flight and the cached fields are still blank —
    // never show an empty hero while the screen is actually loading data.
    val showSkeleton = isRefreshing && (displayName.isBlank() || email.isBlank())

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surface
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Avatar — keeps the default placeholder mounted underneath the request
            // so loading and network failures never leave an empty circle.
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = "Profile picture",
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                if (safeAvatarUrl.isNotBlank() && !avatarFailed) {
                    val avatarRequest = remember(safeAvatarUrl, avatarRetry) {
                        ImageRequest.Builder(context)
                            // The fragment changes Coil's cache key but is not sent
                            // to the avatar server, so a failed first request can
                            // be retried without disabling normal image caching.
                            .data("$safeAvatarUrl#avatarRetry=$avatarRetry")
                            // 140dp avatar ≈ 420px @3x — bound the decode instead
                            // of loading the raw URL at full resolution.
                            .size(256, 256)
                            .build()
                    }
                    AsyncImage(
                        model = avatarRequest,
                        contentDescription = "Profile picture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = {
                            if (avatarRetry == 0) {
                                avatarRetry = 1
                            } else {
                                avatarFailed = true
                            }
                        }
                    )
                }
            }

            when {
                showSkeleton -> {
                    SkeletonBar(width = 180.dp, height = 28.dp)
                    SkeletonBar(width = 140.dp, height = 16.dp)
                }

                !isLoggedIn -> {
                    Text(
                        text = "Not logged in",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Sign in with your YouTube account to access your playlists and profile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }

                else -> {
                    Text(
                        text = displayName.ifBlank { "YouTube Account" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )
                    if (email.isNotBlank()) {
                        Text(
                            text = email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/** Pulsing skeleton bar used in place of name/email text while profile data loads. */
@Composable
private fun SkeletonBar(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "profileSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "profileSkeletonAlpha"
    )

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha),
                shape = RoundedCornerShape(6.dp)
            )
    )
}
