package com.faybish.vibealarm.ui.ringing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

/**
 * What the user sees when an alarm rings with the screen on: the current time,
 * the alarm's label, and two large targets.
 *
 * The background uses the alarm's chosen colour. Custom images are handled in a
 * later milestone; they need a fallback anyway, because user-storage files are
 * unreadable before the first unlock after a reboot.
 */
@Composable
fun RingingScreen(
    alarm: AlarmEntity?,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val background = alarm?.let { Color(it.backgroundColorArgb) }
        ?: MaterialTheme.colorScheme.background
    // The background is user-chosen, so the text colour has to be derived from it
    // rather than taken from the theme — otherwise the clock can end up dark on dark
    // and the alarm screen is unreadable at 6am.
    val foreground = if (background.luminance() > 0.5f) Color.Black else Color.White

    val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val now by produceState(initialValue = LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1_000)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = background, contentColor = foreground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.padding(top = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = formatter.format(now),
                    style = MaterialTheme.typography.displayLarge,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = alarm?.label?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.default_alarm_label),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.height(72.dp),
                    border = BorderStroke(1.dp, foreground.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground),
                ) {
                    Icon(Icons.Filled.Snooze, contentDescription = null, Modifier.size(28.dp))
                    Text(
                        text = stringResource(R.string.action_snooze),
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Button(onClick = onDismiss, modifier = Modifier.height(72.dp)) {
                    Icon(Icons.Filled.AlarmOff, contentDescription = null, Modifier.size(28.dp))
                    Text(
                        text = stringResource(R.string.action_dismiss),
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
