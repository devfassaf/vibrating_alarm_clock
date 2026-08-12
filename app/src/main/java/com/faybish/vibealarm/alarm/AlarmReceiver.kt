package com.faybish.vibealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.domain.SessionEvent
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Entry point for the armed trigger and for the notification action buttons.
 *
 * A broadcast receiver's implicit wake lock ends when [onReceive] returns, so we
 * take our own before handing off — on a cold start after a reboot the service
 * may need a moment to come up, and the device must not fall back asleep.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(AlarmIntents.EXTRA_ALARM_ID, 0)
        if (alarmId == 0L) return
        val instanceId = intent.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)

        val event = when (intent.action) {
            AlarmIntents.ACTION_FIRE -> SessionEvent.Fire(Instant.now())
            AlarmIntents.ACTION_SNOOZE -> SessionEvent.UserSnooze(Instant.now())
            AlarmIntents.ACTION_DISMISS -> SessionEvent.UserDismiss(Instant.now())
            else -> return
        }

        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        val pending = goAsync()
        val runtime = AppGraph.sessionRuntime
        AppGraph.appScope.launch {
            try {
                runtime.handle(alarmId, event, runtime.ServiceControlSink(), instanceId)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
            }
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "VibeAlarm:receiver"
        const val WAKE_LOCK_TIMEOUT_MS = 60_000L
    }
}
