package com.faybish.vibealarm.data

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/**
 * What the app is allowed to still be telling the user about.
 *
 * This decides three things at once: whether the banner appears, whether the notification
 * behind it is still meant to be there, and therefore what the red dot on the launcher icon
 * means. A row that leaks through here is a dot with nothing behind it; a row wrongly
 * filtered out is a morning that went wrong and was never mentioned.
 */
class MissedNoticesTest {

    private val alarm = AlarmEntity(id = 1, label = "שבת", timeMinutesOfDay = 450)

    private fun instance(
        id: Long = 1,
        alarmId: Long = 1,
        occurrence: Long = 1_000,
        state: Int = InstanceState.DONE,
        snoozesUsed: Int = 0,
        endedReason: Int? = EndedReason.AUTO_DISMISSED,
        endedAt: Long? = 2_000,
        noticeAckAt: Long? = null,
    ) = AlarmInstanceEntity(
        id = id,
        alarmId = alarmId,
        occurrenceEpochMillis = occurrence,
        state = state,
        snoozesUsed = snoozesUsed,
        nextActionEpochMillis = occurrence,
        endedReason = endedReason,
        endedAt = endedAt,
        noticeAckAt = noticeAckAt,
    )

    @Test
    fun `a chain that ended by itself is reported as unattended`() {
        val notices = missedNotices(listOf(instance(snoozesUsed = 2)), listOf(alarm))

        assertThat(notices).hasSize(1)
        val notice = notices.single()
        assertThat(notice.kind).isEqualTo(NoticeKind.UNATTENDED)
        assertThat(notice.label).isEqualTo("שבת")
        assertThat(notice.occurrence).isEqualTo(Instant.ofEpochMilli(1_000))
        assertThat(notice.endedAt).isEqualTo(Instant.ofEpochMilli(2_000))
        // Rings, not snoozes: the first one had no snooze in front of it.
        assertThat(notice.ringCount).isEqualTo(3)
    }

    @Test
    fun `an alarm that never rang is a different kind of notice`() {
        val notices = missedNotices(
            listOf(instance(endedReason = EndedReason.MISSED, endedAt = null)),
            listOf(alarm),
        )

        assertThat(notices.single().kind).isEqualTo(NoticeKind.NEVER_RANG)
        assertThat(notices.single().endedAt).isNull()
    }

    /** Preempted means another alarm took the moment — this one never rang either. */
    @Test
    fun `a preempted chain is reported as never rang`() {
        val notices = missedNotices(
            listOf(instance(endedReason = EndedReason.PREEMPTED)),
            listOf(alarm),
        )

        assertThat(notices.single().kind).isEqualTo(NoticeKind.NEVER_RANG)
    }

    /** They were there and switched it off; there is nothing to tell them in the morning. */
    @Test
    fun `an alarm the user dismissed produces no notice`() {
        val notices = missedNotices(
            listOf(instance(endedReason = EndedReason.USER_DISMISSED)),
            listOf(alarm),
        )

        assertThat(notices).isEmpty()
    }

    @Test
    fun `a chain still running produces no notice`() {
        val notices = missedNotices(
            listOf(instance(state = InstanceState.SNOOZED, endedReason = null, endedAt = null)),
            listOf(alarm),
        )

        assertThat(notices).isEmpty()
    }

    @Test
    fun `an acknowledged notice is gone`() {
        val notices = missedNotices(listOf(instance(noticeAckAt = 3_000)), listOf(alarm))

        assertThat(notices).isEmpty()
    }

    /** Nothing to name it with, and no alarm left to fix. */
    @Test
    fun `a notice whose alarm was deleted is dropped`() {
        val notices = missedNotices(listOf(instance(alarmId = 99)), listOf(alarm))

        assertThat(notices).isEmpty()
    }

    @Test
    fun `several notices come newest first`() {
        val second = alarm.copy(id = 2, label = "חול")

        val notices = missedNotices(
            listOf(
                instance(id = 1, alarmId = 1, occurrence = 1_000),
                instance(id = 2, alarmId = 2, occurrence = 5_000),
                instance(id = 3, alarmId = 1, occurrence = 3_000),
            ),
            listOf(alarm, second),
        )

        assertThat(notices.map { it.instanceId }).containsExactly(2L, 3L, 1L).inOrder()
    }

    /**
     * The SQL filter and the pure mapper answer the same question and must keep answering it
     * identically: a reason the query accepts and the mapper drops is a red dot on the
     * launcher whose row the banner never renders.
     */
    @Test
    fun `the query's reason list is exactly what the mapper reports on`() {
        val allReasons = listOf(
            EndedReason.AUTO_DISMISSED,
            EndedReason.USER_DISMISSED,
            EndedReason.MISSED,
            EndedReason.PREEMPTED,
        )

        assertThat(allReasons.filter { noticeKindOf(it) != null })
            .containsExactlyElementsIn(EndedReason.NOTICE_WORTHY)
        assertThat(noticeKindOf(null)).isNull()
    }

    /** Ended before the column existed: still a notice, just without an end time to quote. */
    @Test
    fun `a chain from before the upgrade still reports`() {
        val notices = missedNotices(listOf(instance(endedAt = null)), listOf(alarm))

        assertThat(notices.single().kind).isEqualTo(NoticeKind.UNATTENDED)
        assertThat(notices.single().endedAt).isNull()
    }
}
