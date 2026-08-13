package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.faybish.vibealarm.R

/**
 * Asked when a card with unsaved edits is about to be closed.
 *
 * Closing silently would be the app's worst failure mode: the user would remember moving
 * the time and the alarm would ring at the old one. Three ways out, all named — and the
 * buttons are stacked because "discard the changes" does not fit next to two others in
 * either language.
 */
@Composable
fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(stringResource(R.string.unsaved_changes)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onSave) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.action_discard))
                }
                TextButton(onClick = onKeepEditing) {
                    Text(stringResource(R.string.action_keep_editing))
                }
            }
        },
    )
}
