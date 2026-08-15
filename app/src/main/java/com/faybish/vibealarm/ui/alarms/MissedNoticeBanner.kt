package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.MissedNotice
import com.faybish.vibealarm.data.NoticeKind
import com.faybish.vibealarm.ui.format.NoticeText

/**
 * "You missed an alarm at 07:30" — inside the app, where the red dot sends people.
 *
 * The notification alone was not enough. Samsung shows notifications as a pill that is gone
 * in about two seconds, and nothing in the app repeated what it said: the user was left with
 * a badge on the launcher icon, an app that looked completely normal, and no way to find out
 * what either meant or how to make it go away.
 *
 * So the banner is the notification's twin — same sentence, same source ([NoticeText]) — and
 * dismissing it is what clears the notification, and with it the dot. Opening the app does
 * not clear anything on its own: evidence that disappears before it is read is the problem
 * this exists to solve.
 */
@Composable
fun MissedNoticeBanner(
    notice: MissedNotice,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val unattended = notice.kind == NoticeKind.UNATTENDED

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // Louder than the snooze banner's tertiary: this one is about a morning that
            // already went wrong, not about one that is still coming.
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (unattended) Icons.Filled.NotificationsOff else Icons.Filled.AlarmOff,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = if (unattended) {
                        NoticeText.unattendedTitle(context, notice.occurrence)
                    } else {
                        NoticeText.neverRangTitle(context)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (unattended) {
                        NoticeText.unattendedDetail(
                            context = context,
                            label = notice.label,
                            firstRingAt = notice.occurrence,
                            endedAt = notice.endedAt,
                            ringCount = notice.ringCount,
                        )
                    } else {
                        NoticeText.neverRangDetail(context, notice.label, notice.occurrence)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onAcknowledge) {
                Text(stringResource(R.string.action_got_it))
            }
        }
    }
}
