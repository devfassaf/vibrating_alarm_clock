package com.faybish.vibealarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.ReliabilityLogger
import kotlinx.coroutines.launch

/**
 * Re-arms everything after a reboot.
 *
 * Registered for LOCKED_BOOT_COMPLETED as well as BOOT_COMPLETED: the locked one
 * arrives before the user has entered their PIN, which is exactly the case this
 * app has to survive. It works only because every component on this path is
 * direct-boot aware and all state lives in device-protected storage.
 *
 * Both broadcasts may arrive; [AlarmScheduler.armAll] is idempotent.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        val pending = goAsync()
        AppGraph.reliabilityLogger.log(ReliabilityLogger.BOOT_RECEIVED, action)
        AppGraph.appScope.launch {
            try {
                AppGraph.notifications.ensureChannels()
                AppGraph.repository.ensurePresetsSeeded()
                AppGraph.scheduler.armAll(AppGraph.sessionRuntime)
            } finally {
                pending.finish()
            }
        }
    }
}
