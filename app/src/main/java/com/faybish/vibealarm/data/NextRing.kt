package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.NextOccurrenceCalculator
import java.time.Instant
import java.time.ZoneId

/**
 * When this phone will ring next, across every alarm — the header's one number.
 *
 * The armed instances are the primary truth: SCHEDULED and SNOOZED rows carry exactly the
 * trigger AlarmManager holds, so a pending snooze correctly beats a scheduled alarm that
 * fires later — the header answers "when will it ring", not "when is the next occurrence".
 *
 * The fallback computes from the enabled alarms directly, for the moments bookkeeping has
 * no armed row: mid-transition, or an install where arming failed and only resumeAll will
 * repair it. Pure, so the snooze-beats-schedule decision is pinned by a JVM test.
 */
fun nextRing(
    instances: List<AlarmInstanceEntity>,
    alarms: List<AlarmEntity>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): Instant? {
    val armed = instances
        .filter { it.state == InstanceState.SCHEDULED || it.state == InstanceState.SNOOZED }
        .minOfOrNull { it.nextActionEpochMillis }
    if (armed != null) return Instant.ofEpochMilli(armed)

    return alarms
        .filter { it.enabled }
        .mapNotNull { alarm ->
            NextOccurrenceCalculator.nextTrigger(ScheduleCodec.decode(alarm), now, zone)
        }
        .minOrNull()
}
