@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ca.ilianokokoro.umihi.music.ui.components.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ca.ilianokokoro.umihi.music.R

/**
 * Prompts the user that a new version is available, with three actions:
 * **Update now** (start the APK download), **Later** (dismiss — the next
 * check may ask again) and **Skip this version** (persist the dismissal).
 */
@Composable
fun UpdateAvailableDialog(
    version: String,
    notes: String?,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onSkipThisVersion: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(text = stringResource(R.string.update_available))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.new_version_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = version,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (!notes.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onUpdateNow,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.update_now))
            }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = onSkipThisVersion,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.skip_this_version))
                }
                TextButton(
                    onClick = onLater,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.later))
                }
            }
        },
        properties = DialogProperties(dismissOnClickOutside = true)
    )
}

/**
 * Shown after the APK download finished while the app is in the foreground —
 * offers an immediate install instead of making the user find the notification.
 */
@Composable
fun UpdateReadyDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.update_ready_title))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.update_ready_body),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onInstall,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.install))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.later))
            }
        },
        properties = DialogProperties(dismissOnClickOutside = true)
    )
}
