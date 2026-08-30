package com.faybish.vibealarm.domain

/**
 * Which posted morning-after notifications no longer have an unread row behind them.
 *
 * The notification is state the app never reads back: it lives in the system's shade, and
 * every code path that retires a notice row is trusted to also cancel it. When one path
 * forgets — older versions pruned week-old rows and deleted alarms without cancelling —
 * the launcher keeps a badge that nothing inside the app explains or can remove. This is
 * the reverse reconciliation: given what is actually posted and what is actually unread,
 * name the notifications that are lying.
 *
 * Pure, so the decision is testable without a NotificationManager: the caller enumerates
 * the posted ids and cancels what this returns.
 */
object NoticeReconciliation {

    /** Mirrors AlarmNotifications' id layout; asserted against it by a test. */
    const val MISSED_ID_BASE = 300_000
    const val UNATTENDED_ID_BASE = 400_000
    private const val RANGE = 100_000

    /**
     * @param postedIds every notification id this app currently has in the shade.
     * @param unreadAlarmIds alarms that still have at least one unread notice row.
     * @return the posted notice ids to cancel. Ids outside the two notice ranges — the
     *   firing and snoozed notifications — are never returned: they are owned by the live
     *   ring, and cancelling them from here would silence an alarm in progress.
     */
    fun orphanedNoticeIds(postedIds: List<Int>, unreadAlarmIds: Set<Long>): List<Int> =
        postedIds.filter { id ->
            val alarmId = when (id) {
                in MISSED_ID_BASE until MISSED_ID_BASE + RANGE -> (id - MISSED_ID_BASE).toLong()
                in UNATTENDED_ID_BASE until UNATTENDED_ID_BASE + RANGE ->
                    (id - UNATTENDED_ID_BASE).toLong()
                else -> return@filter false
            }
            alarmId !in unreadAlarmIds
        }
}
