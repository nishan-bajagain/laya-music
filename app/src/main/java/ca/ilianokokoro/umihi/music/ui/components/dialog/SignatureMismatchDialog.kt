package ca.ilianokokoro.umihi.music.ui.components.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import ca.ilianokokoro.umihi.music.R

/**
 * Shown when the user tries to install a downloaded update whose signing
 * certificate differs from the installed app's. Android would refuse the
 * install with a bare "package conflicts" error, so instead of firing it we
 * explain the one-time situation and guide the user through: back up their
 * data → uninstall → reinstall the new version.
 *
 * This dialog only ever appears for users of the accidentally debug-signed
 * v1.0.3 build — every other install path is seamless.
 */
@Composable
fun SignatureMismatchDialog(
    backupInProgress: Boolean,
    onBackup: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.update_signature_mismatch_title))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.update_signature_mismatch_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.update_signature_mismatch_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (backupInProgress) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.backup_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onBackup,
                enabled = !backupInProgress,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.backup_data))
            }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = onUninstall,
                    enabled = !backupInProgress,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(
                        stringResource(R.string.uninstall_laya_music),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !backupInProgress,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(stringResource(R.string.later))
                }
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false)
    )
}
