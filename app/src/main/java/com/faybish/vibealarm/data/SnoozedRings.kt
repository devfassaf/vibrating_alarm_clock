package com.faybish.vibealarm.data

import java.time.Instant

/**
 * A chain that is waiting to ring again, as the list screen needs to describe it.
 *
 * @param remainingSnoozes null for "until dismissed", which has no number to count down.
 */
data class SnoozedRing(
    val alarmId: Long,
    val instanceId: Long,
    val label: String,
    val ringsAt: Instant,
    val remainingSnoozes: Int?,
)

/**
 * Joins snoozed instances to their alarms.
 *
 * Pure so the arithmetic that produces "2 snoozes left" can be tested without a database:
 * the count is the difference between what the alarm allows and what this chain has used,
 * and getting it wrong would put a number in front of the user that contradicts what the
 * alarm then does.
 */
fun snoozedRings(
    instances: List<AlarmInstanceEntity>,
    alarms: List<AlarmEntity>,
): List<SnoozedRing> = instances
    .filter { it.state == InstanceState.SNOOZED }
    .mapNotNull { instance ->
        val alarm = alarms.firstOrNull { it.id == instance.alarmId } ?: return@mapNotNull null
        SnoozedRing(
            alarmId = alarm.id,
            instanceId = instance.id,
            label = alarm.label,
            ringsAt = Instant.ofEpochMilli(instance.nextActionEpochMillis),
            remainingSnoozes = if (alarm.snoozeRepeatCount < 0) {
                null
            } else {
                (alarm.snoozeRepeatCount - instance.snoozesUsed).coerceAtLeast(0)
            },
        )
    }
    .sortedBy { it.ringsAt }
