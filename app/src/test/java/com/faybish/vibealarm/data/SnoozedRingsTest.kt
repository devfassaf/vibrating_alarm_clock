package com.faybish.vibealarm.data

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/**
 * What the list screen says about a snooze that has not rung yet.
 *
 * The count it shows has to agree with what the alarm will actually do — a banner promising
 * "2 snoozes left" over an alarm that rings once more is worse than no banner.
 */
class SnoozedRingsTest {

    private val ringsAt = Instant.parse("2026-08-15T04:35:00Z")

    private fun alarm(id: Long, repeats: Int, label: String = "שבת") = AlarmEntity(
        id = id,
        label = label,
        timeMinutesOfDay = 7 * 60 + 30,
        snoozeRepeatCount = repeats,
    )

    private fun instance(
        id: Long,
        alarmId: Long,
        state: Int = InstanceState.SNOOZED,
        snoozesUsed: Int = 1,
        at: Instant = ringsAt,
    ) = AlarmInstanceEntity(
        id = id,
        alarmId = alarmId,
        occurrenceEpochMillis = at.minusSeconds(600).toEpochMilli(),
        state = state,
        snoozesUsed = snoozesUsed,
        nextActionEpochMillis = at.toEpochMilli(),
    )

    @Test
    fun `a snoozed chain is described by when it rings and what is left`() {
        val rings = snoozedRings(
            instances = listOf(instance(id = 9, alarmId = 4, snoozesUsed = 1)),
            alarms = listOf(alarm(id = 4, repeats = 3)),
        )

        val ring = rings.single()
        assertThat(ring.alarmId).isEqualTo(4)
        assertThat(ring.instanceId).isEqualTo(9)
        assertThat(ring.label).isEqualTo("שבת")
        assertThat(ring.ringsAt).isEqualTo(ringsAt)
        assertThat(ring.remainingSnoozes).isEqualTo(2)
    }

    /** "Until dismissed" has no number to count down, and must not invent one. */
    @Test
    fun `an endless chain reports no remaining count`() {
        val rings = snoozedRings(
            instances = listOf(instance(id = 1, alarmId = 1, snoozesUsed = 7)),
            alarms = listOf(alarm(id = 1, repeats = SnoozeRepeatsForTest.UNTIL_DISMISSED)),
        )

        assertThat(rings.single().remainingSnoozes).isNull()
    }

    /** A chain that has used more than its budget still says zero, never a negative. */
    @Test
    fun `the remaining count never goes below zero`() {
        val rings = snoozedRings(
            instances = listOf(instance(id = 1, alarmId = 1, snoozesUsed = 9)),
            alarms = listOf(alarm(id = 1, repeats = 3)),
        )

        assertThat(rings.single().remainingSnoozes).isEqualTo(0)
    }

    /** Only a snoozed chain is offered for cancelling — the rest have nothing to cancel. */
    @Test
    fun `chains that are not snoozed are ignored`() {
        val states = listOf(InstanceState.SCHEDULED, InstanceState.FIRING, InstanceState.DONE)

        val rings = snoozedRings(
            instances = states.mapIndexed { index, state ->
                instance(id = index + 1L, alarmId = 1, state = state)
            },
            alarms = listOf(alarm(id = 1, repeats = 3)),
        )

        assertThat(rings).isEmpty()
    }

    /** A row whose alarm was deleted must not become a banner about nothing. */
    @Test
    fun `an orphaned instance is dropped`() {
        val rings = snoozedRings(
            instances = listOf(instance(id = 1, alarmId = 404)),
            alarms = listOf(alarm(id = 1, repeats = 3)),
        )

        assertThat(rings).isEmpty()
    }

    @Test
    fun `the soonest ring comes first`() {
        val later = ringsAt.plusSeconds(600)
        val rings = snoozedRings(
            instances = listOf(
                instance(id = 1, alarmId = 1, at = later),
                instance(id = 2, alarmId = 2, at = ringsAt),
            ),
            alarms = listOf(alarm(id = 1, repeats = 3), alarm(id = 2, repeats = 3)),
        )

        assertThat(rings.map { it.instanceId }).containsExactly(2L, 1L).inOrder()
    }

    /** Kept local so this test does not depend on the domain module's naming. */
    private object SnoozeRepeatsForTest {
        const val UNTIL_DISMISSED = -1
    }
}
