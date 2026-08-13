package com.faybish.vibealarm.ui.alarms

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Time picker in a dialog, honouring the device's 12/24-hour setting.
 *
 * Two ways in, and the user picks: the clock face for a rough time chosen by feel, or the
 * keypad for typing 06:42 exactly. The toggle is one icon rather than a settings row, and
 * the choice is remembered — which way you prefer is something you demonstrate by using
 * it, so the next alarm opens the way the last one did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = is24Hour,
    )

    val settings = AppGraph.settings
    val scope = rememberCoroutineScopeForDialog()
    // null until the stored preference has been read: opening on the clock face and then
    // flipping to the keypad under the user's finger would be worse than a blank moment.
    var byKeyboard by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { byKeyboard = settings.timeInputByKeyboard.first() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        text = {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                when (byKeyboard) {
                    true -> TimeInput(state = state)
                    false -> TimePicker(state = state)
                    null -> Unit
                }
                byKeyboard?.let { keyboard ->
                    Row {
                        IconButton(
                            onClick = {
                                byKeyboard = !keyboard
                                scope.launch { settings.setTimeInputByKeyboard(!keyboard) }
                            },
                        ) {
                            Icon(
                                imageVector = if (keyboard) Icons.Filled.Schedule else Icons.Filled.Keyboard,
                                contentDescription = stringResource(
                                    if (keyboard) R.string.action_use_clock else R.string.action_use_keyboard,
                                ),
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Named apart so the dialog body above reads as layout rather than plumbing. */
@Composable
private fun rememberCoroutineScopeForDialog() = androidx.compose.runtime.rememberCoroutineScope()
