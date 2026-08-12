package com.faybish.vibealarm.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.update.ReleaseInfo

/**
 * The update surface: one dialog per state. Deliberately plain — it appears on the
 * alarm list, never over a ringing alarm (that is a separate activity), and never while
 * an alarm is due shortly.
 */
@Composable
fun UpdateDialogHost(
    state: UpdateUiState,
    installedVersion: String?,
    onDownload: (ReleaseInfo) -> Unit,
    onSkip: (ReleaseInfo) -> Unit,
    onDismiss: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    when (state) {
        UpdateUiState.Idle, UpdateUiState.Installing -> Unit

        UpdateUiState.Checking -> InfoDialog(
            title = stringResource(R.string.update_checking),
            body = null,
            onDismiss = onDismiss,
        )

        UpdateUiState.AlreadyCurrent -> InfoDialog(
            title = stringResource(R.string.update_up_to_date),
            body = installedVersion?.let { stringResource(R.string.update_installed_version, it) },
            onDismiss = onDismiss,
        )

        is UpdateUiState.Available -> AvailableDialog(
            release = state.release,
            installedVersion = installedVersion,
            onDownload = { onDownload(state.release) },
            onSkip = { onSkip(state.release) },
            onDismiss = onDismiss,
        )

        is UpdateUiState.Postponed -> InfoDialog(
            title = stringResource(R.string.update_postponed_title, state.release.version),
            body = stringResource(R.string.update_postponed_body),
            onDismiss = onDismiss,
        )

        is UpdateUiState.Downloading -> DownloadingDialog(state)

        is UpdateUiState.Problem -> ProblemDialog(
            kind = state.kind,
            onDismiss = onDismiss,
            onOpenInstallSettings = onOpenInstallSettings,
        )
    }
}

@Composable
private fun AvailableDialog(
    release: ReleaseInfo,
    installedVersion: String?,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title, release.version)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (installedVersion != null) {
                    Text(
                        text = stringResource(R.string.update_installed_version, installedVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                val lines = release.whatsNew.flatMap { it.lines }
                if (lines.isEmpty()) {
                    Text(stringResource(R.string.update_no_notes))
                } else {
                    Text(
                        text = stringResource(R.string.update_whats_new),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (release.sizeBytes > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.update_size_mb, release.sizeBytes / 1_000_000f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.update_action_install)) }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_later)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.update_action_skip)) }
            }
        },
    )
}

@Composable
private fun DownloadingDialog(state: UpdateUiState.Downloading) {
    AlertDialog(
        onDismissRequest = {}, // interrupting mid-download would leave a partial file
        title = { Text(stringResource(R.string.update_downloading, state.release.version)) },
        text = {
            Column {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.update_downloaded_of,
                        state.bytes / 1_000_000f,
                        state.total / 1_000_000f,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ProblemDialog(
    kind: ProblemKind,
    onDismiss: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val body = stringResource(
        when (kind) {
            ProblemKind.NO_NETWORK -> R.string.update_problem_network
            ProblemKind.DOWNLOAD_FAILED -> R.string.update_problem_download
            ProblemKind.TRUNCATED -> R.string.update_problem_truncated
            ProblemKind.INSTALL_PERMISSION -> R.string.update_problem_permission
            ProblemKind.INSTALL_FAILED -> R.string.update_problem_install
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_problem_title)) },
        text = { Text(body) },
        confirmButton = {
            if (kind == ProblemKind.INSTALL_PERMISSION) {
                TextButton(onClick = { onOpenInstallSettings(); onDismiss() }) {
                    Text(stringResource(R.string.action_open_settings))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = {
            if (kind == ProblemKind.INSTALL_PERMISSION) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun InfoDialog(title: String, body: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
