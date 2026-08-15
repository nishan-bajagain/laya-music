package ca.ilianokokoro.umihi.music.ui.navigation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import ca.ilianokokoro.umihi.music.BuildConfig
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.ImageErrorLog
import ca.ilianokokoro.umihi.music.core.helpers.LogHelper.printe
import ca.ilianokokoro.umihi.music.core.managers.DataBackupManager
import ca.ilianokokoro.umihi.music.core.managers.UpdateManager
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository
import ca.ilianokokoro.umihi.music.ui.components.dialog.SignatureMismatchDialog
import ca.ilianokokoro.umihi.music.ui.components.dialog.UpdateAvailableDialog
import ca.ilianokokoro.umihi.music.ui.components.dialog.UpdateReadyDialog
import ca.ilianokokoro.umihi.music.ui.components.miniplayer.MiniPlayerWrapper
import ca.ilianokokoro.umihi.music.ui.navigation.viewmodels.SharedViewModel
import ca.ilianokokoro.umihi.music.ui.screens.about.AboutScreen
import ca.ilianokokoro.umihi.music.ui.screens.auth.AuthScreen
import ca.ilianokokoro.umihi.music.ui.screens.donation.DonationScreen
import ca.ilianokokoro.umihi.music.ui.screens.home.HomeScreen
import ca.ilianokokoro.umihi.music.ui.screens.player.PlayerScreen
import ca.ilianokokoro.umihi.music.ui.screens.playlist.PlaylistScreen
import ca.ilianokokoro.umihi.music.ui.screens.profile.ProfileScreen
import ca.ilianokokoro.umihi.music.ui.screens.search.SearchScreen
import ca.ilianokokoro.umihi.music.ui.screens.settings.SettingsScreen
import ca.ilianokokoro.umihi.music.ui.screens.welcome.WelcomeScreen


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationRoot(
    modifier: Modifier = Modifier,
    showWelcome: Boolean = false,
    isAuthenticated: Boolean = false
) {
    val sharedViewModel: SharedViewModel = viewModel()
    // Unauthenticated users now land on the branded sign-in page. It contains
    // the app overview and a clear Google/YouTube login CTA, instead of showing
    // a separate welcome screen that immediately redirects to login.
    val initialKey: NavKey = if (!isAuthenticated) AuthScreenKey else HomeScreenKey
    val backStack = rememberNavBackStack(initialKey)
    val app = LocalContext.current.applicationContext as Application
    val scope = rememberCoroutineScope()

    // App self-update state — collected at the root so the update popup can
    // appear on any screen. Gated by BuildConfig so the store flavor is inert.
    val updateInfo by UpdateManager.availableUpdate.collectAsStateWithLifecycle()
    val readyApk by UpdateManager.readyToInstallApk.collectAsStateWithLifecycle()
    val signatureMismatchApk by UpdateManager.signatureMismatchApk.collectAsStateWithLifecycle()
    val currentScreen = backStack.last()
    val screenConfig = rememberScreenUiConfig(currentScreen)

    var showFullPlayer by remember { mutableStateOf(false) }
    // State for the guided one-time migration (v1.0.3 debug-key builds):
    // backing up app data before the required uninstall/reinstall.
    var backupInProgress by remember { mutableStateOf(false) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupInProgress = true
                val result = DataBackupManager.export(app, uri)
                backupInProgress = false
                Toast.makeText(
                    app,
                    result.fold(
                        onSuccess = { app.getString(R.string.backup_success) },
                        onFailure = { app.getString(R.string.backup_failed) }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    var bottomBarHeightPixels by remember { mutableIntStateOf(0) }
    val bottomBarHeightDp = with(LocalDensity.current) { bottomBarHeightPixels.toDp() }
    val playerSheetState =
        rememberBottomSheetState(
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            initialValue = SheetValue.Hidden
        )


    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->

        val miniPlayerBottomPadding by animateDpAsState(
            targetValue = if (!screenConfig.showBottomBar) {
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            } else {
                bottomBarHeightDp
            },
            animationSpec = tween(Constants.Animation.NAVIGATION_DURATION)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            NavDisplay(
                modifier = Modifier
                    .fillMaxSize(),
                backStack = backStack,
                onBack = backStack::safePop,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                // Performance: slide+fade is significantly cheaper than scale
                // animations (no GPU layer composition required), giving smoother
                // transitions on lower-end devices while still looking premium.
                transitionSpec = {
                    (slideInHorizontally(
                        animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                        initialOffsetX = { it / 4 }
                    ) + fadeIn(animationSpec = tween(Constants.Animation.NAVIGATION_DURATION))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                                targetOffsetX = { -it / 4 }
                            ) + fadeOut(animationSpec = tween(Constants.Animation.NAVIGATION_DURATION)))
                },
                popTransitionSpec = {
                    (slideInHorizontally(
                        animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                        initialOffsetX = { -it / 4 }
                    ) + fadeIn(animationSpec = tween(Constants.Animation.NAVIGATION_DURATION))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                                targetOffsetX = { it / 4 }
                            ) + fadeOut(animationSpec = tween(Constants.Animation.NAVIGATION_DURATION)))
                },
                predictivePopTransitionSpec = {
                    (slideInHorizontally(
                        animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                        initialOffsetX = { -it / 4 }
                    ) + fadeIn(animationSpec = tween(Constants.Animation.NAVIGATION_DURATION))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                                targetOffsetX = { it / 4 }
                            ) + fadeOut(animationSpec = tween(Constants.Animation.NAVIGATION_DURATION)))
                },
                entryProvider = { key ->
                    when (key) {

                        is HomeScreenKey -> NavEntry(key) {
                            HomeScreen(
                                sharedViewModel = sharedViewModel,
                                onSettingsButtonPress = { backStack.add(SettingsScreenKey) },
                                onProfilePress = { backStack.add(ProfileScreenKey) },
                                onLogin = { backStack.add(AuthScreenKey) },
                                onPlaylistPressed = { playlist ->
                                    backStack.add(PlaylistScreenKey(playlistInfo = playlist))
                                },
                                application = app
                            )
                        }

                        is SettingsScreenKey -> NavEntry(key) {
                            SettingsScreen(
                                openAuthScreen = { backStack.add(AuthScreenKey) },
                                openAboutScreen = { backStack.add(AboutScreenKey) },
                                openDonationScreen = { backStack.add(DonationScreenKey) },
                                application = app
                            )
                        }

                        is AboutScreenKey -> NavEntry(key) {
                            AboutScreen(
                                onBack = backStack::safePop,
                                openDonationScreen = { backStack.add(DonationScreenKey) }
                            )
                        }

                        is DonationScreenKey -> NavEntry(key) {
                            DonationScreen(onBack = backStack::safePop)
                        }

                        is ProfileScreenKey -> NavEntry(key) {
                            ProfileScreen(
                                onBack = backStack::safePop,
                                onLoggedOut = {
                                    sharedViewModel.requestPlaylistRefresh()
                                    // Logout: wipe the entire back stack and land on AuthScreen
                                    // ProfileScreen will also call onBack() after this, but
                                    // safePop refuses when AuthScreenKey is the only entry.
                                    repeat(backStack.size) { backStack.removeLastOrNull() }
                                    backStack.add(AuthScreenKey)
                                },
                                onLogin = { backStack.add(AuthScreenKey) },
                                application = app
                            )
                        }

                        is PlaylistScreenKey -> NavEntry(key) {
                            PlaylistScreen(
                                sharedViewModel = sharedViewModel,
                                playlistInfo = key.playlistInfo,
                                onBack = backStack::safePop,
                                onOpenPlayer = { showFullPlayer = true },
                                application = app
                            )
                        }

                        is AuthScreenKey -> NavEntry(key) {
                            // Hide back button when AuthScreen is the root (no screen behind it)
                            val isRoot = backStack.first() == key
                            AuthScreen(
                                onBack = backStack::safePop,
                                showBackButton = !isRoot,
                                sharedViewModel = sharedViewModel,
                                onLoginSuccess = {
                                    // Clear entire back stack and land on Home
                                    repeat(backStack.size) { backStack.removeLastOrNull() }
                                    backStack.add(HomeScreenKey)
                                },
                                application = app
                            )
                        }

                        is SearchScreenKey -> NavEntry(key) {
                            SearchScreen(
                                application = app,
                            )
                        }

                        is WelcomeScreenKey -> NavEntry(key) {
                            val context = LocalContext.current
                            WelcomeScreen(
                                onGetStarted = {
                                    scope.launch {
                                        ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository(context)
                                            .markWelcomeSeen()
                                    }
                                    // After Welcome, require login — never jump straight to Home
                                    backStack.removeLastOrNull()
                                    backStack.add(AuthScreenKey)
                                }
                            )
                        }

                        else -> throw RuntimeException(
                            app.getString(
                                R.string.invalid_navkey,
                                key
                            )
                        )
                    }
                }
            )

            if (BuildConfig.DEBUG) {
                // Debug-only: surface the last image-fetch failure (HTTP
                // status or exception + URL) on screen so the exact reason
                // posters are blank can be read/screenshotted without adb.
                val lastImageError by ImageErrorLog.lastError.collectAsStateWithLifecycle()
                if (lastImageError != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Image error: $lastImageError",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            MiniPlayerWrapper(
                showMiniPlayer = screenConfig.showMiniPlayer && !showFullPlayer,
                onMiniPlayerPressed = { showFullPlayer = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = miniPlayerBottomPadding)
            )


            AnimatedVisibility(
                visible = screenConfig.showBottomBar,
                enter = slideInVertically(
                    animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                    initialOffsetY = { it }
                ),
                exit = slideOutVertically(
                    animationSpec = tween(Constants.Animation.NAVIGATION_DURATION),
                    targetOffsetY = { it }
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomNavigationBar(
                    currentTab = screenConfig.selectedTab,
                    onTabSelected = { key ->
                        if (backStack.last() != key) {
                            backStack.add(key)
                        }
                    },
                    modifier = Modifier.onSizeChanged { bottomBarHeightPixels = it.height }
                )
            }
        }

    }



    if (showFullPlayer) {
        ModalBottomSheet(
            sheetMaxWidth = Dp.Unspecified,
            onDismissRequest = {
                showFullPlayer = false
            },
            sheetState = playerSheetState
        ) {
            PlayerScreen(onBack = { showFullPlayer = false }, application = app)
        }
    }

    if (BuildConfig.SELF_UPDATE_ENABLED) {
        updateInfo?.let { info ->
            UpdateAvailableDialog(
                version = info.version,
                notes = info.notes,
                onUpdateNow = {
                    UpdateManager.startUpdateDownload(app)
                    UpdateManager.dismissUpdatePrompt()
                },
                onLater = UpdateManager::dismissUpdatePrompt,
                onSkipThisVersion = {
                    scope.launch {
                        DatastoreRepository(app)
                            .saveDismissedUpdateVersion(info.version)
                    }
                    UpdateManager.dismissUpdatePrompt()
                }
            )
        }

        readyApk?.let { apk ->
            UpdateReadyDialog(
                onInstall = {
                    UpdateManager.installUpdate(app, apk)
                    UpdateManager.dismissReadyToInstall()
                },
                onDismiss = UpdateManager::dismissReadyToInstall
            )
        }

        // A downloaded update signed with a different key than the installed
        // app (the one-time v1.0.3 debug-key situation) — guide instead of
        // letting Android show a bare "package conflicts" error.
        signatureMismatchApk?.let {
            SignatureMismatchDialog(
                backupInProgress = backupInProgress,
                onBackup = { backupLauncher.launch("laya-music-backup.zip") },
                onUninstall = { launchUninstall(app) },
                onDismiss = UpdateManager::dismissSignatureMismatch
            )
        }
    }

}

/**
 * Opens the system's "Uninstall app" confirmation for Laya Music itself.
 * Only ever used as the final step of the one-time signature-migration flow
 * (v1.0.3 debug-key users). If the user cancels, nothing happens.
 */
private fun launchUninstall(context: Context) {
    try {
        val intent = Intent(
            Intent.ACTION_UNINSTALL_PACKAGE,
            "package:${context.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        printe("Could not launch uninstaller: ${e.message}", exception = e)
        Toast.makeText(
            context,
            context.getString(R.string.uninstall_manual_hint),
            Toast.LENGTH_LONG
        ).show()
    }
}


fun NavBackStack<NavKey>.safePop() {
    if (this.size > 1) {
        this.removeLastOrNull()
    } else {
        printe("Backstack Pop was called unsafely")
    }
}
