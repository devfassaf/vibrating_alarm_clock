package com.faybish.vibealarm.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Append-only event log powering the Reliability screen and the reboot test. */
class ReliabilityLogger(
    private val logDao: LogDao,
    private val scope: CoroutineScope,
) {
    fun log(event: String, detail: String = "") {
        Log.i(TAG, "$event ${detail.take(200)}")
        scope.launch {
            try {
                logDao.insert(ReliabilityLogEntity(event = event, detail = detail))
                logDao.pruneOlderThan(System.currentTimeMillis() - RETENTION_MS)
            } catch (e: Exception) {
                Log.w(TAG, "failed to persist log entry", e)
            }
        }
    }

    companion object {
        const val TAG = "VibeAlarm"
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        // Event names (grep-able in logcat via the shared TAG)
        const val BOOT_RECEIVED = "BOOT_RECEIVED"
        const val ARMED = "ARMED"
        const val FIRED = "FIRED"
        const val SNOOZED = "SNOOZED"
        const val AUTO_DISMISSED = "AUTO_DISMISSED"
        const val USER_DISMISSED = "USER_DISMISSED"
        const val MISSED = "MISSED"
        const val PREEMPTED = "PREEMPTED"
        const val TIME_CHANGED = "TIME_CHANGED"
        const val PACKAGE_REPLACED = "PACKAGE_REPLACED"
        const val FGS_DENIED = "FGS_DENIED"
        const val FALLBACK_SOUND_USED = "FALLBACK_SOUND_USED"
        const val EXACT_ALARM_BLOCKED = "EXACT_ALARM_BLOCKED"
    }
}
