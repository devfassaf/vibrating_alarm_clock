package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R

/** Which question is being asked about one alarm, and about which alarm. */
sealed interface PendingAlarmAction {
    val alarmId: Long

    /** The long-press menu: duplicate or delete. */
    data class Choose(override val alarmId: Long) : PendingAlarmAction

    /** "Delete this alarm?" — reached from the menu and from the card's own button. */
    data class ConfirmDelete(override val alarmId: Long) : PendingAlarmAction
}

/**
 * What a long press on an alarm offers: copy it, or throw it away.
 *
 * A long press is a deliberate gesture, so the dialog leads with the alarm it is about —
 * on a list of alarms that differ by fifteen minutes, "which one did I just hold?" is the
 * only question worth answering before either action.
 */
@Composable
fun AlarmActionsDialog(
    alarmDescription: String,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(alarmDescription) },
        text = {
            Column {
                ActionRow(
                    icon = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.action_duplicate),
                    onClick = onDuplicate,
                )
                ActionRow(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onDelete,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * Deleting an alarm cannot be undone, and the gesture that led here — a long press on one
 * row of a list — is the easiest one in the app to aim wrongly. So the alarm is named again
 * here: the answer to "delete?" depends entirely on which one it is.
 */
@Composable
fun ConfirmDeleteDialog(
    alarmDescription: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = { Text(alarmDescription) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
