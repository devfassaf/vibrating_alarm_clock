package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
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
import com.faybish.vibealarm.R

/**
 * Asks for one number, in whatever unit the caller is asking about.
 *
 * Save stays disabled until [parse] accepts what has been typed, so a typo cannot become an
 * alarm that rings for a fifth of a second or snoozes two hundred times. The presets beside
 * it answer the common cases; this is here for the ones they miss.
 */
@Composable
fun NumberInputDialog(
    title: String,
    fieldLabel: String,
    minimum: Int,
    maximum: Int,
    initial: Int,
    parse: (String) -> Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(initial.coerceIn(minimum, maximum).toString()) }
    val parsed = parse(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    isError = parsed == null && text.isNotEmpty(),
                    label = { Text(fieldLabel) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                Text(stringResource(R.string.custom_range_hint, minimum, maximum))
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
