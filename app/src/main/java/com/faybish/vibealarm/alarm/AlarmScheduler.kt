package com.faybish.vibealarm.alarm

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmInstanceEntity
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.data.ScheduleType
import com.faybish.vibealarm.domain.NextOccurrenceCalculator
import com.faybish.vibealarm.domain.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every interaction with [AlarmManager].
 *
 * Design choices that matter for reliability:
 *  - only `setAlarmClock` is used: it is fully exempt from Doze and App Standby
 *    throttling, and it surfaces the system's next-alarm indicator;
 *  - one PendingIntent per alarm rather than a single global "next alarm", so a
 *    broken link in one alarm's chain cannot silence the others;
 *  - [armAll] is idempotent and is the single entry point after boot, time
 *    changes, package replacement, permission changes and every data edit.
 */
class AlarmScheduler(
    private val context: Context,
    private val repository: AlarmRepository,
    private val logger: ReliabilityLogger,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {

    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    /**
     * Serializes everything that reads-then-writes an alarm's instance row, shared
     * with [SessionRuntime].
     *
     * Two paths legitimately arm at the same time — the boot receiver and the app
     * opening, for instance — and `scheduleNextOccurrence` clears the old instance
     * before inserting the new one. Run concurrently, both inserted, leaving two
     * live instances for one alarm: the armed trigger carries one id while lookups
     * could return the other, and the orphan then reported itself missed forever.
     *
     * Public entry points take the lock; the `*Locked` variants assume it is held,
     * because a Kotlin Mutex is not reentrant.
     */
    internal val mutex = Mutex()

    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    /**
     * Re-derives and re-arms the whole schedule from persisted state: resumes
     * chains that were mid-flight, arms the next occurrence of every other
     * enabled alarm, and cancels triggers for disabled ones.
     */
    suspend fun armAll(runtime: SessionRuntime) = mutex.withLock { armAllLocked(runtime) }

    private suspend fun armAllLocked(runtime: SessionRuntime) {
        repository.pruneOldInstances()

        if (!canScheduleExactAlarms()) {
            logger.log(ReliabilityLogger.EXACT_ALARM_BLOCKED, "exact alarms not permitted")
            return
        }

        runtime.resumeAllLocked()

        val enabled = repository.getEnabledAlarms()
        for (alarm in enabled) {
            if (repository.activeInstance(alarm.id) == null) scheduleNextOccurrenceLocked(alarm)
        }

        val enabledIds = enabled.map { it.id }.toSet()
        for (alarm in repository.getAllAlarms()) {
            if (alarm.id !in enabledIds) cancel(alarm.id)
        }
    }

    /**
     * Computes the alarm's next occurrence, records a SCHEDULED instance and arms it.
     *
     * @param afterFiring true when called at the end of a ring/snooze chain. A
     *   one-time alarm disables itself then instead of rolling over to tomorrow.
     */
    suspend fun scheduleNextOccurrence(alarm: AlarmEntity, afterFiring: Boolean = false) =
        mutex.withLock { scheduleNextOccurrenceLocked(alarm, afterFiring) }

    internal suspend fun scheduleNextOccurrenceLocked(
        alarm: AlarmEntity,
        afterFiring: Boolean = false,
    ) {
        if (afterFiring && alarm.scheduleType == ScheduleType.ONE_TIME) {
            disable(alarm.id, "one-time alarm consumed")
            return
        }

        val pruned = pruneElapsedDates(alarm)
        val schedule = repository.scheduleOf(pruned)
        val next = NextOccurrenceCalculator.nextTrigger(schedule, Instant.now(), zoneProvider())

        if (next == null) {
            disable(pruned.id, "no future occurrence")
            return
        }

        repository.clearActiveInstance(pruned.id)
        val instanceId = repository.saveInstance(
            AlarmInstanceEntity(
                alarmId = pruned.id,
                occurrenceEpochMillis = next.toEpochMilli(),
                state = InstanceState.SCHEDULED,
                nextActionEpochMillis = next.toEpochMilli(),
            ),
        )
        arm(pruned.id, instanceId, next)
    }

    /** Arms (or re-arms) the single trigger belonging to this alarm. */
    fun arm(alarmId: Long, instanceId: Long, at: Instant) {
        if (!canScheduleExactAlarms()) {
            logger.log(ReliabilityLogger.EXACT_ALARM_BLOCKED, "alarm $alarmId not armed")
            return
        }
        val operation = AlarmIntents.firePendingIntent(context, alarmId, instanceId)
        val info = AlarmManager.AlarmClockInfo(
            at.toEpochMilli(),
            AlarmIntents.appPendingIntent(context, alarmId),
        )
        alarmManager.setAlarmClock(info, operation)
        logger.log(ReliabilityLogger.ARMED, "alarm=$alarmId instance=$instanceId at=$at")
    }

    fun cancel(alarmId: Long) {
        AlarmIntents.cancelFirePendingIntent(context, alarmId)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    suspend fun onAlarmSaved(alarm: AlarmEntity) = mutex.withLock {
        cancel(alarm.id)
        repository.clearActiveInstance(alarm.id)
        if (alarm.enabled) scheduleNextOccurrenceLocked(alarm)
    }

    suspend fun onAlarmDeleted(alarmId: Long) = mutex.withLock {
        cancel(alarmId)
        repository.deleteAlarm(alarmId)
    }

    suspend fun onAlarmToggled(alarmId: Long, enabled: Boolean) = mutex.withLock {
        repository.setAlarmEnabled(alarmId, enabled)
        cancel(alarmId)
        if (enabled) repository.getAlarm(alarmId)?.let { scheduleNextOccurrenceLocked(it) }
    }

    private suspend fun disable(alarmId: Long, reason: String) {
        repository.setAlarmEnabled(alarmId, false)
        cancel(alarmId)
        logger.log(ReliabilityLogger.ARMED, "alarm=$alarmId disabled ($reason)")
    }

    /**
     * Drops dates that already elapsed, so a date-list alarm eventually reports
     * "nothing left" instead of re-evaluating the same past dates forever.
     */
    private suspend fun pruneElapsedDates(alarm: AlarmEntity): AlarmEntity {
        val schedule = repository.scheduleOf(alarm)
        if (schedule !is Schedule.Dates) return alarm
        val today = LocalDate.now(zoneProvider())
        val remaining = schedule.dates.filter { !it.isBefore(today) }
        if (remaining.size == schedule.dates.size) return alarm
        val updated = ScheduleCodec.encode(schedule.copy(dates = remaining), alarm)
        repository.saveAlarm(updated)
        return updated
    }
}
