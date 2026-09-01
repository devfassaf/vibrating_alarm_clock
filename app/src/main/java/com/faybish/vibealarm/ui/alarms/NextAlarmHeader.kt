package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.ui.format.timeUntil
import androidx.compose.ui.platform.LocalContext
import java.time.Instant

/**
 * The screen's one-line answer to "when will this phone ring next".
 *
 * Counts armed snoozes too, because it reports the next actual ring, not the next scheduled
 * occurrence — a snoozed 6:30 alarm coming back at 6:35 IS the next alarm, and a header
 * claiming "in 8 hours" while the phone is four minutes from buzzing would be lying. With
 * nothing armed and nothing enabled it says so instead, which is Samsung's pattern the
 * header borrows: a silent phone should look silent from the top of the screen.
 */
@Composable
fun NextAlarmHeader(
    nextRingAt: Instant?,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        if (nextRingAt == null) {
            Text(
                text = stringResource(R.string.next_alarm_none),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(
                    R.string.next_alarm_in,
                    // Never negative: a trigger a moment past due reads as "now", not as
                    // garbage, for the second or two before the ring takes over.
                    timeUntil(context, maxOf(nextRingAt, now), now),
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
