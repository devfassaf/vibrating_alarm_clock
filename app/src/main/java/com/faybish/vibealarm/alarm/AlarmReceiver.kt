package com.faybish.vibealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.ReliabilityLogger
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
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        val wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }

        val pending = goAsync()
        AppGraph.appScope.launch {
            try {
                when (action) {
                    // The trigger is handed straight to the service without running the
                    // state machine here. Only the service owns the vibrator, so it has
                    // to be the one that performs the Fire transition — running it here
                    // first would mark the alarm as FIRING and turn the service's own
                    // transition into a no-op, leaving a silent alarm.
                    AlarmIntents.ACTION_FIRE -> startRinging(context, alarmId, instanceId)

                    AlarmIntents.ACTION_SNOOZE ->
                        dispatch(alarmId, instanceId, SessionEvent.UserSnooze(Instant.now()))

                    AlarmIntents.ACTION_DISMISS ->
                        dispatch(alarmId, instanceId, SessionEvent.UserDismiss(Instant.now()))
                }
            } catch (e: Exception) {
                AppGraph.reliabilityLogger.log(
                    ReliabilityLogger.FGS_DENIED,
                    "alarm=$alarmId action=$action ${e.javaClass.simpleName}: ${e.message}",
                )
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                pending.finish()
            }
        }
    }

    private suspend fun startRinging(context: Context, alarmId: Long, instanceId: Long) {
        val alarm = AppGraph.repository.getAlarm(alarmId)
        if (alarm == null) {
            AppGraph.reliabilityLogger.log(ReliabilityLogger.MISSED, "alarm=$alarmId no longer exists")
            AppGraph.scheduler.cancel(alarmId)
            return
        }
        AlarmServiceStarter.start(
            context = context,
            alarm = alarm,
            instanceId = instanceId,
            logger = AppGraph.reliabilityLogger,
            notifications = AppGraph.notifications,
        )
    }

    private suspend fun dispatch(alarmId: Long, instanceId: Long, event: SessionEvent) {
        val runtime = AppGraph.sessionRuntime
        runtime.handle(alarmId, event, runtime.ServiceControlSink(), instanceId)
    }

    private companion object {
        const val WAKE_LOCK_TAG = "VibeAlarm:receiver"
        const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        val HANDLED_ACTIONS = setOf(
            AlarmIntents.ACTION_FIRE,
            AlarmIntents.ACTION_SNOOZE,
            AlarmIntents.ACTION_DISMISS,
        )
    }
}
