package ca.ilianokokoro.umihi.music.ui.screens.settings

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FeaturedPlayList
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ca.ilianokokoro.umihi.music.R
import ca.ilianokokoro.umihi.music.core.Constants
import ca.ilianokokoro.umihi.music.core.managers.DataBackupManager
import ca.ilianokokoro.umihi.music.data.repositories.DatastoreRepository.PreferenceKeys
import ca.ilianokokoro.umihi.music.ui.components.ErrorMessage
import ca.ilianokokoro.umihi.music.ui.components.FadingStatusBarWrapper
import ca.ilianokokoro.umihi.music.ui.components.LoadingAnimation
import ca.ilianokokoro.umihi.music.ui.components.dialog.ConfirmDialog
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.BooleanSettingItem
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.SettingsItem
import ca.ilianokokoro.umihi.music.ui.screens.settings.components.SettingsSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun SettingsScreen(
    openAuthScreen: () -> Unit,
    openAboutScreen: () -> Unit = {},
    openDonationScreen: () -> Unit = {},
    application: Application,
    settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(application))
) {
    val uiState = settingsViewModel.uiState.collectAsStateWithLifecycle().value

    // Manual data backup/restore — the user's own copy of playlists, settings
    // and downloads (also the way v1.0.3 debug-key users keep their data
    // across the one-time uninstall/reinstall migration).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupInProgress by remember { mutableStateOf(false) }
    var restoreInProgress by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupInProgress = true
                val result = DataBackupManager.export(application, uri)
                backupInProgress = false
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { context.getString(R.string.backup_success) },
                        onFailure = { context.getString(R.string.backup_failed) }
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                restoreInProgress = true
                val result = DataBackupManager.import(application, uri)
                restoreInProgress = false
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { context.getString(R.string.restore_success) },
                        onFailure = { context.getString(R.string.restore_failed) }
                    ),
                    Toast.LENGTH_LONG
                ).show()
                if (result.isSuccess) {
                    // Restart so Room/DataStore reopen the restored files.
                    delay(500)
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                        ?.apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                        }
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                        Runtime.getRuntime().exit(0)
                    }
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settingsViewModel.getSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    FadingStatusBarWrapper { statusBarHeight ->
        Scaffold(
            contentWindowInsets = WindowInsets(0.dp)
        ) { paddingValues ->
            when (val screenState = uiState.screenState) {
                ScreenState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingAnimation()
                    }
                }

                is ScreenState.Error -> {
                    ErrorMessage(
                        ex = screenState.exception,
                        onRetry = settingsViewModel::getSettings
                    )
                }

                is ScreenState.Success -> {
                    // Pending path chosen in the storage dialog — held until the user confirms
                    // the warning dialog or cancels.
                    var pendingStoragePath by remember { mutableStateOf<String?>(null) }
                    var showStorageWarning by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(
                                start = 16.dp,
                                end = 16.dp,
                                top = statusBarHeight,
                                bottom = Constants.Ui.SCROLLABLE_BOTTOM_PADDING + paddingValues.calculateBottomPadding()
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        // ── Support ───────────────────────────────────────────────
                        SettingsSection(title = stringResource(R.string.donate_title)) {
                            SettingsItem(
                                title = stringResource(R.string.donate_title),
                                subtitle = stringResource(R.string.donate_subtitle),
                                leadingIcon = Icons.Outlined.Favorite,
                                onClick = openDonationScreen
                            )
                        }

                        // ── General ───────────────────────────────────────────────
                        SettingsSection(title = stringResource(R.string.general)) {
                            BooleanSettingItem(
                                title = stringResource(R.string.show_podcast_playlist_title),
                                subtitle = stringResource(R.string.show_podcast_playlist_description),
                                leadingIcon = Icons.AutoMirrored.Outlined.FeaturedPlayList,
                                value = screenState.settings.showPodcastPlaylist,
                                onToggle = {
                                    settingsViewModel.updateSetting(
                                        PreferenceKeys.SHOW_PODCAST_PLAYLIST, it
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            BooleanSettingItem(
                                title = stringResource(R.string.keep_screen_on_title),
                                subtitle = stringResource(R.string.keep_screen_on_title_description),
                                leadingIcon = Icons.Outlined.StayCurrentPortrait,
                                value = screenState.settings.keepScreenOn,
                                onToggle = settingsViewModel::updateKeepScreenOnSetting
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            BooleanSettingItem(
                                title = stringResource(R.string.auto_update_title),
                                subtitle = stringResource(R.string.check_update_setting_description),
                                leadingIcon = Icons.Outlined.SystemUpdate,
                                value = screenState.settings.autoCheckForUpdates,
                                onToggle = {
                                    settingsViewModel.updateSetting(
                                        PreferenceKeys.AUTO_CHECK_UPDATES, it
                                    )
                                }
                            )
                        }

                        // ── Playback ──────────────────────────────────────────────
                        SettingsSection(title = stringResource(R.string.playback)) {
                            BooleanSettingItem(
                                title = stringResource(R.string.enable_audio_offload),
                                subtitle = stringResource(R.string.audio_offload_subtitle),
                                leadingIcon = Icons.Outlined.Memory,
                                value = screenState.settings.useAudioOffload,
                                onToggle = settingsViewModel::updateAudioOffloadSetting
                            )
                        }

                        // ── Data & Storage ────────────────────────────────────────
                        SettingsSection(title = stringResource(R.string.data_and_storage)) {
                            BooleanSettingItem(
                                title = stringResource(R.string.download_on_metered_title),
                                subtitle = stringResource(R.string.download_on_metered_description),
                                leadingIcon = Icons.Outlined.CloudDownload,
                                value = screenState.settings.downloadOnMetered,
                                onToggle = {
                                    settingsViewModel.updateSetting(
                                        PreferenceKeys.DOWNLOAD_ON_METERED, it
                                    )
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            // Download location picker
                            val currentPath = screenState.settings.downloadPath
                            val currentLabel = uiState.storageOptions
                                .firstOrNull { it.second == currentPath }?.first
                                ?: stringResource(R.string.internal_storage)
                            SettingsItem(
                                title = stringResource(R.string.download_location),
                                subtitle = currentLabel,
                                leadingIcon = Icons.Outlined.FolderOpen,
                                onClick = settingsViewModel::showStorageDialog
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            SettingsItem(
                                title = stringResource(R.string.delete_downloads),
                                subtitle = stringResource(R.string.clear_data_message),
                                leadingIcon = Icons.Outlined.Delete,
                                onClick = { settingsViewModel.updateShowDownloadDeleteConfirm(true) }
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            SettingsItem(
                                title = stringResource(R.string.backup_data),
                                subtitle = stringResource(R.string.backup_data_subtitle),
                                leadingIcon = Icons.Outlined.SaveAlt,
                                onClick = {
                                    if (!backupInProgress && !restoreInProgress) {
                                        backupLauncher.launch("laya-music-backup.zip")
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            SettingsItem(
                                title = stringResource(R.string.restore_data),
                                subtitle = stringResource(R.string.restore_data_subtitle),
                                leadingIcon = Icons.Outlined.Restore,
                                onClick = {
                                    if (!backupInProgress && !restoreInProgress) {
                                        restoreLauncher.launch(
                                            arrayOf("application/zip", "application/octet-stream")
                                        )
                                    }
                                }
                            )
                        }

                        // ── App ───────────────────────────────────────────────────
                        SettingsSection(title = stringResource(R.string.app_info)) {
                            val versionName = try {
                                application.packageManager
                                    .getPackageInfo(application.packageName, 0)
                                    .versionName ?: "—"
                            } catch (_: Exception) { "—" }
                            SettingsItem(
                                title = stringResource(R.string.current_version),
                                subtitle = versionName,
                                leadingIcon = Icons.Outlined.Info,
                                onClick = {}
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            SettingsItem(
                                title = stringResource(R.string.about_laya),
                                subtitle = stringResource(R.string.about_laya_subtitle),
                                leadingIcon = Icons.Outlined.Info,
                                onClick = openAboutScreen
                            )
                        }

                        // Dialogs
                        if (uiState.showDownloadDeleteConfirm) {
                            ConfirmDialog(
                                title = stringResource(R.string.download_clear_confirm_title),
                                text = stringResource(R.string.download_clear_confirm_text),
                                onConfirm = {
                                    settingsViewModel.clearDownloads()
                                    settingsViewModel.updateShowDownloadDeleteConfirm(false)
                                },
                                onDismiss = {
                                    settingsViewModel.updateShowDownloadDeleteConfirm(false)
                                }
                            )
                        }

                        if (uiState.showStorageDialog) {
                            StorageLocationDialog(
                                options = uiState.storageOptions,
                                currentPath = screenState.settings.downloadPath,
                                onSelect = { path ->
                                    // Determine whether the user actually changed storage.
                                    // Internal storage is represented as null in DataStore;
                                    // normalise both sides before comparing.
                                    val internalPath = uiState.storageOptions.firstOrNull()?.second
                                    val effectiveCurrent =
                                        screenState.settings.downloadPath ?: internalPath
                                    val effectiveNew = path ?: internalPath
                                    settingsViewModel.hideStorageDialog()
                                    if (effectiveNew != effectiveCurrent) {
                                        // Different storage selected — ask for confirmation
                                        pendingStoragePath = path
                                        showStorageWarning = true
                                    } else {
                                        // Same location chosen, no-op
                                    }
                                },
                                onDismiss = settingsViewModel::hideStorageDialog
                            )
                        }

                        if (showStorageWarning) {
                            AlertDialog(
                                onDismissRequest = {
                                    showStorageWarning = false
                                    pendingStoragePath = null
                                },
                                title = { Text(stringResource(R.string.storage_change_warning_title)) },
                                text = { Text(stringResource(R.string.storage_change_warning_text)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        settingsViewModel.setDownloadPath(pendingStoragePath)
                                        showStorageWarning = false
                                        pendingStoragePath = null
                                    }) {
                                        Text(stringResource(R.string.storage_switch_anyway))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showStorageWarning = false
                                        pendingStoragePath = null
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageLocationDialog(
    options: List<Pair<String, String>>,
    currentPath: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val internalPath = options.firstOrNull()?.second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_storage)) },
        text = {
            Column {
                options.forEach { (label, path) ->
                    val isInternal = path == internalPath
                    val effectiveCurrent = currentPath ?: internalPath
                    val selected = path == effectiveCurrent
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(if (isInternal) null else path)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { onSelect(if (isInternal) null else path) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}
