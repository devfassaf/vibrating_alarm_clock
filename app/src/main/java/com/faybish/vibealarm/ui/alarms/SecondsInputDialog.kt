package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.AutoSilence

/**
 * Asks for a ring duration in seconds.
 *
 * Save stays disabled until the number is one the app will actually honour, so a typo
 * cannot become an alarm that rings for a fifth of a second.
 */
@Composable
fun SecondsInputDialog(
    initialSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initialSeconds.toString()) }
    val parsed = AutoSilence.parse(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.field_auto_silence_custom)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    isError = parsed == null && text.isNotEmpty(),
                    label = { Text(stringResource(R.string.field_seconds)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(
                    text = stringResource(
                        R.string.field_auto_silence_custom_hint,
                        AutoSilence.MIN_SECONDS,
                        AutoSilence.MAX_SECONDS,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
