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

        /**
         * The ringing screen reached the user. Logged by the screen itself because neither
         * launch route reports success: a full-screen intent the platform declines is
         * declined silently, and a refused background activity start throws nothing. Without
         * this the log cannot answer "did ring two show anything?" on a phone we cannot
         * attach a debugger to.
         */
        const val SCREEN_SHOWN = "SCREEN_SHOWN"

        /**
         * Starting the ringing screen from the service threw. Distinct from FGS_DENIED —
         * that one means the ringing *service* was refused, i.e. a possibly silent morning,
         * and a benign overlay refusal must not masquerade as it in the log the user reads.
         */
        const val SCREEN_DENIED = "SCREEN_DENIED"
        const val SNOOZED = "SNOOZED"
        const val AUTO_DISMISSED = "AUTO_DISMISSED"
        const val UNATTENDED = "UNATTENDED"
        const val USER_DISMISSED = "USER_DISMISSED"
        const val MISSED = "MISSED"
        const val PREEMPTED = "PREEMPTED"
        const val TIME_CHANGED = "TIME_CHANGED"
        const val PACKAGE_REPLACED = "PACKAGE_REPLACED"
        const val FGS_DENIED = "FGS_DENIED"
        const val EFFECT_FAILED = "EFFECT_FAILED"
        const val FALLBACK_SOUND_USED = "FALLBACK_SOUND_USED"
        const val EXACT_ALARM_BLOCKED = "EXACT_ALARM_BLOCKED"
        const val UPDATE_CHECK = "UPDATE_CHECK"
        const val UPDATE_INSTALL = "UPDATE_INSTALL"
    }
}
