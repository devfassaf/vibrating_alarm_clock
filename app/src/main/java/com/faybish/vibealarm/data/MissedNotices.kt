package com.faybish.vibealarm.data

import java.time.Instant

/** Which of the two nights this notice is about. */
enum class NoticeKind {
    /** It rang, the whole chain played out, and nobody switched it off. */
    UNATTENDED,

    /** It never rang at all — the phone was off, or another alarm took the moment. */
    NEVER_RANG,
}

/**
 * A morning-after notice, as the list screen needs to say it.
 *
 * @param ringCount rings including the first; meaningless for [NoticeKind.NEVER_RANG].
 * @param endedAt null for a chain that ended before this column existed (or never rang).
 */
data class MissedNotice(
    val alarmId: Long,
    val instanceId: Long,
    val label: String,
    val kind: NoticeKind,
    val occurrence: Instant,
    val endedAt: Instant?,
    val ringCount: Int,
)

/**
 * The notices waiting to be read, newest first.
 *
 * Pure, because the decision of what counts as unread is the whole feature: the same row
 * drives the notification, the red dot on the launcher icon, and the banner — and if the
 * three disagree, the user is left with a dot that nothing in the app explains, which is
 * exactly the state this replaces.
 *
 * A row whose alarm has since been deleted is dropped: there is nothing to name it, and
 * the alarm it was about no longer exists to be fixed.
 */
fun missedNotices(
    instances: List<AlarmInstanceEntity>,
    alarms: List<AlarmEntity>,
): List<MissedNotice> = instances
    .filter { it.noticeAckAt == null }
    .mapNotNull { instance ->
        val alarm = alarms.firstOrNull { it.id == instance.alarmId } ?: return@mapNotNull null
        val kind = when (instance.endedReason) {
            EndedReason.AUTO_DISMISSED -> NoticeKind.UNATTENDED
            EndedReason.MISSED, EndedReason.PREEMPTED -> NoticeKind.NEVER_RANG
            else -> return@mapNotNull null
        }
        MissedNotice(
            alarmId = alarm.id,
            instanceId = instance.id,
            label = alarm.label,
            kind = kind,
            occurrence = Instant.ofEpochMilli(instance.occurrenceEpochMillis),
            endedAt = instance.endedAt?.let(Instant::ofEpochMilli),
            ringCount = instance.snoozesUsed + 1,
        )
    }
    .sortedByDescending { it.occurrence }
