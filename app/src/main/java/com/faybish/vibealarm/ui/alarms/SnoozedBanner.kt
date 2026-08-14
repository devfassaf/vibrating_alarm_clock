package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.ui.format.formatInstantTime
import com.faybish.vibealarm.ui.format.timeUntil
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * "This alarm comes back at 07:35 — call it off?"
 *
 * A snoozed chain used to be reachable only by waiting for it to ring and then dismissing
 * it, which on a morning you are already up for means being woken a second time by an alarm
 * you had finished with. This is that decision, available now, at the top of the screen
 * where it cannot be missed.
 *
 * The absolute time is what the sentence leads with: a countdown alone goes stale the moment
 * the screen is left open, and being wrong about *when* is worse than being coarse.
 */
@Composable
fun SnoozedBanner(
    label: String,
    ringsAt: Instant,
    remainingSnoozes: Int?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Recomputed on a slow tick so a screen left open does not keep claiming "in 3 minutes".
    val now by produceState(initialValue = Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(TICK_MILLIS)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Snooze, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = stringResource(
                        R.string.snoozed_banner_title,
                        formatInstantTime(ringsAt),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = listOfNotNull(
                        label.takeIf { it.isNotBlank() },
                        timeUntil(context, ringsAt, now),
                        remainingSnoozes?.let {
                            context.resources.getQuantityString(
                                R.plurals.snoozed_banner_remaining,
                                it,
                                it,
                            )
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel_snooze))
            }
        }
    }
}

private const val TICK_MILLIS = 20_000L
