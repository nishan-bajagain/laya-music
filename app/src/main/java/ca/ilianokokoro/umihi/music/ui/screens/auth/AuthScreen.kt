@file:OptIn(ExperimentalMaterial3Api::class)

package ca.ilianokokoro.umihi.music.ui.screens.auth

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.ui.components.BackButton
import ca.ilianokokoro.umihi.music.ui.navigation.viewmodels.SharedViewModel
import kotlinx.coroutines.flow.collectLatest
import coil3.compose.AsyncImage

@Composable
fun AuthScreen(
    onBack: () -> Unit,
    application: Application,
    sharedViewModel: SharedViewModel,
    showBackButton: Boolean = true,
    onLoginSuccess: (() -> Unit)? = null,
    authViewModel: AuthViewModel = viewModel(factory = AuthViewModel.Factory(application))
) {
    val context = LocalContext.current
    val isDarkMode = isSystemInDarkTheme()
    var showLoginPage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        authViewModel.eventFlow.collectLatest { event ->
            when (event) {
                AuthViewModel.ScreenEvent.Out.LoginCompleted -> {
                    Toast.makeText(context, R.string.login_success, Toast.LENGTH_SHORT).show()
                    sharedViewModel.requestPlaylistRefresh()
                    // If a dedicated post-login callback is provided (e.g. root auth gate),
                    // use it; otherwise just pop back to the previous screen.
                    if (onLoginSuccess != null) {
                        onLoginSuccess()
                    } else {
                        onBack()
                    }
                }
            }
        }
    }

    val webView = remember(showLoginPage) {
        if (!showLoginPage) return@remember null
        val themedContext = ContextThemeWrapper(
            context,
            if (isDarkMode) {
                R.style.Theme_WebView_Dark
            } else {
                R.style.Theme_WebView_Light
            }
        )

        WebView(themedContext).apply {

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true

                useWideViewPort = true
                loadWithOverviewMode = true

                setSupportZoom(false)
                displayZoomControls = false
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    evaluateJavascript(
                        "window.yt?.config_?.DATASYNC_ID"
                    ) { result ->
                        authViewModel.onDataSyncIdFound(result)
                    }

                    authViewModel.onPageFinished(url)
                }
            }

            loadUrl(Constants.Auth.START_URL)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    if (showLoginPage) stringResource(R.string.log_in)
                    else stringResource(R.string.welcome_to_laya)
                )
            },
            navigationIcon = {
                if (showBackButton || showLoginPage) {
                    BackButton(
                        onBack = {
                            if (showLoginPage) showLoginPage = false else onBack()
                        }
                    )
                }
            }
        )
    }) { paddingValues ->
        if (!showLoginPage) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                AsyncImage(
                    model = R.mipmap.icon,
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier
                        .size(88.dp)
                        .padding(4.dp)
                )
                Text(
                    text = stringResource(R.string.welcome_to_laya),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.login_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AuthBenefit(
                            icon = Icons.Rounded.MusicNote,
                            title = stringResource(R.string.login_benefit_streaming),
                            body = stringResource(R.string.login_benefit_streaming_body)
                        )
                        AuthBenefit(
                            icon = Icons.Rounded.Sync,
                            title = stringResource(R.string.login_benefit_lyrics),
                            body = stringResource(R.string.login_benefit_lyrics_body)
                        )
                        AuthBenefit(
                            icon = Icons.Rounded.CloudDownload,
                            title = stringResource(R.string.login_benefit_offline),
                            body = stringResource(R.string.login_benefit_offline_body)
                        )
                    }
                }

                Button(
                    onClick = { showLoginPage = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.continue_with_google),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = stringResource(R.string.login_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                factory = { webView!! },
                onRelease = { view ->
                    view.stopLoading()
                    view.onPause()
                    view.destroy()
                }
            )
        }
    }
}

@Composable
private fun AuthBenefit(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


