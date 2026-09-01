package com.faybish.vibealarm.data

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

/**
 * The header's one number: when the phone rings next.
 *
 * The load-bearing decision is that an armed snooze counts — the header answers "when will
 * it ring", and a snoozed 6:30 alarm coming back at 6:35 IS the next ring even while a 7:15
 * alarm is scheduled. A header reading "in 8 hours" four minutes before the phone buzzes
 * would be lying about the only thing it exists to say.
 */
class NextRingTest {

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
    private val now: Instant = Instant.parse("2026-09-01T04:00:00Z")

    private fun instance(state: Int, at: Instant, alarmId: Long = 1) = AlarmInstanceEntity(
        alarmId = alarmId,
        occurrenceEpochMillis = at.toEpochMilli(),
        state = state,
        nextActionEpochMillis = at.toEpochMilli(),
    )

    @Test
    fun `the earliest armed trigger wins`() {
        val ring = nextRing(
            instances = listOf(
                instance(InstanceState.SCHEDULED, now.plusSeconds(8 * 3600), alarmId = 1),
                instance(InstanceState.SCHEDULED, now.plusSeconds(2 * 3600), alarmId = 2),
            ),
            alarms = emptyList(),
            now = now,
            zone = zone,
        )

        assertThat(ring).isEqualTo(now.plusSeconds(2 * 3600))
    }

    /** The user's decision: a pending snooze is the next alarm, not a footnote. */
    @Test
    fun `an armed snooze beats a scheduled alarm that fires later`() {
        val ring = nextRing(
            instances = listOf(
                instance(InstanceState.SCHEDULED, now.plusSeconds(8 * 3600), alarmId = 1),
                instance(InstanceState.SNOOZED, now.plusSeconds(4 * 60), alarmId = 2),
            ),
            alarms = emptyList(),
            now = now,
            zone = zone,
        )

        assertThat(ring).isEqualTo(now.plusSeconds(4 * 60))
    }

    /** A chain mid-ring (FIRING) or finished (DONE) is not a future ring. */
    @Test
    fun `only scheduled and snoozed rows are armed`() {
        val ring = nextRing(
            instances = listOf(
                instance(InstanceState.FIRING, now.minusSeconds(30)),
                instance(InstanceState.DONE, now.plusSeconds(3600)),
            ),
            alarms = emptyList(),
            now = now,
            zone = zone,
        )

        assertThat(ring).isNull()
    }

    /** No armed rows (mid-transition): fall back to computing from the enabled alarms. */
    @Test
    fun `with nothing armed the enabled alarms answer`() {
        val sevenThirty = AlarmEntity(id = 1, enabled = true, timeMinutesOfDay = 7 * 60 + 30)
        val disabledEarlier = AlarmEntity(id = 2, enabled = false, timeMinutesOfDay = 6 * 60)

        val ring = nextRing(emptyList(), listOf(sevenThirty, disabledEarlier), now, zone)

        // 04:00Z is 07:00 in Jerusalem (DST): the 7:30 alarm is half an hour out.
        assertThat(ring).isEqualTo(now.plusSeconds(30 * 60))
    }

    @Test
    fun `nothing armed and nothing enabled means no next ring`() {
        val off = AlarmEntity(id = 1, enabled = false, timeMinutesOfDay = 7 * 60)

        assertThat(nextRing(emptyList(), listOf(off), now, zone)).isNull()
    }

    /** Armed truth outranks computation: the snooze is sooner than any occurrence. */
    @Test
    fun `an armed row silences the fallback entirely`() {
        val weekly = AlarmEntity(id = 1, enabled = true, timeMinutesOfDay = 7 * 60 + 30)

        val ring = nextRing(
            instances = listOf(instance(InstanceState.SNOOZED, now.plusSeconds(120))),
            alarms = listOf(weekly),
            now = now,
            zone = zone,
        )

        assertThat(ring).isEqualTo(now.plusSeconds(120))
    }
}
