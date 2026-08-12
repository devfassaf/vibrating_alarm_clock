package com.faybish.vibealarm.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.ReliabilityLogger
import kotlinx.coroutines.launch

/**
 * Recomputes the schedule whenever the meaning of a stored wall-clock time
 * changes, or whenever the app's ability to schedule it changes:
 * time/timezone edits, our own package being replaced, and the user granting or
 * revoking the exact-alarm permission (which cancels armed alarms outright).
 */
class SystemEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val exactAlarmChanged = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED

        val event = when {
            action == Intent.ACTION_TIME_CHANGED -> ReliabilityLogger.TIME_CHANGED
            action == Intent.ACTION_TIMEZONE_CHANGED -> ReliabilityLogger.TIME_CHANGED
            action == Intent.ACTION_MY_PACKAGE_REPLACED -> ReliabilityLogger.PACKAGE_REPLACED
            exactAlarmChanged -> ReliabilityLogger.EXACT_ALARM_BLOCKED
            else -> return
        }

        val pending = goAsync()
        AppGraph.reliabilityLogger.log(event, action)
        AppGraph.appScope.launch {
            try {
                AppGraph.notifications.ensureChannels()
                AppGraph.scheduler.armAll(AppGraph.sessionRuntime)
            } finally {
                pending.finish()
            }
        }
    }
}
