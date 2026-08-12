package com.faybish.vibealarm.alarm

import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmInstanceEntity
import com.faybish.vibealarm.data.EndedReason
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.domain.EndReason
import com.faybish.vibealarm.domain.SessionPhase
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

/**
 * The database row and the reducer's state must stay in lockstep: a mismatch here
 * would silently break the snooze chain across a reboot.
 */
class SessionMappingTest {

    private val occurrence = Instant.parse("2026-08-12T04:00:00Z")

    private fun instance(
        state: Int = InstanceState.SCHEDULED,
        endedReason: Int? = null,
    ) = AlarmInstanceEntity(
        id = 7,
        alarmId = 3,
        occurrenceEpochMillis = occurrence.toEpochMilli(),
        state = state,
        snoozesUsed = 2,
        nextActionEpochMillis = occurrence.plusSeconds(300).toEpochMilli(),
        endedReason = endedReason,
    )

    @Test
    fun `every phase round-trips through the entity`() {
        val phases = mapOf(
            InstanceState.SCHEDULED to SessionPhase.SCHEDULED,
            InstanceState.FIRING to SessionPhase.FIRING,
            InstanceState.SNOOZED to SessionPhase.SNOOZED,
            InstanceState.DONE to SessionPhase.DONE,
        )
        phases.forEach { (column, phase) ->
            val entity = instance(state = column)
            val state = entity.toState()
            assertThat(state.phase).isEqualTo(phase)
            assertThat(entity.applying(state).state).isEqualTo(column)
        }
    }

    @Test
    fun `every end reason round-trips through the entity`() {
        val reasons = mapOf(
            EndedReason.AUTO_DISMISSED to EndReason.AUTO_DISMISSED,
            EndedReason.USER_DISMISSED to EndReason.USER_DISMISSED,
            EndedReason.MISSED to EndReason.MISSED,
            EndedReason.PREEMPTED to EndReason.PREEMPTED,
        )
        reasons.forEach { (column, reason) ->
            val entity = instance(state = InstanceState.DONE, endedReason = column)
            val state = entity.toState()
            assertThat(state.endedReason).isEqualTo(reason)
            assertThat(entity.applying(state).endedReason).isEqualTo(column)
        }
    }

    @Test
    fun `timestamps and counters survive the round-trip`() {
        val entity = instance(state = InstanceState.SNOOZED)
        val state = entity.toState()
        assertThat(state.occurrence).isEqualTo(occurrence)
        assertThat(state.nextActionAt).isEqualTo(occurrence.plusSeconds(300))
        assertThat(state.snoozesUsed).isEqualTo(2)
        assertThat(state.alarmId).isEqualTo(3)

        val applied = entity.applying(state)
        assertThat(applied.nextActionEpochMillis).isEqualTo(state.nextActionAt.toEpochMilli())
        assertThat(applied.snoozesUsed).isEqualTo(2)
        assertThat(applied.id).isEqualTo(7)
    }

    @Test
    fun `firedAt is stamped when entering FIRING and kept afterwards`() {
        val scheduled = instance(state = InstanceState.SCHEDULED)
        assertThat(scheduled.firedAt).isNull()

        val firing = scheduled.applying(scheduled.toState().copy(phase = SessionPhase.FIRING))
        assertThat(firing.firedAt).isNotNull()

        val snoozed = firing.applying(firing.toState().copy(phase = SessionPhase.SNOOZED))
        assertThat(snoozed.firedAt).isEqualTo(firing.firedAt)
    }

    @Test
    fun `session config comes from the alarm's snooze fields`() {
        val alarm = AlarmEntity(
            timeMinutesOfDay = 420,
            snoozeIntervalMinutes = 3,
            snoozeRepeatCount = 5,
        )
        val config = alarm.toSessionConfig()
        assertThat(config.snoozeInterval).isEqualTo(Duration.ofMinutes(3))
        assertThat(config.snoozeRepeatCount).isEqualTo(5)
    }

    @Test
    fun `infinite snooze count is carried through unchanged`() {
        val alarm = AlarmEntity(timeMinutesOfDay = 420, snoozeRepeatCount = -1)
        assertThat(alarm.toSessionConfig().snoozeRepeatCount).isEqualTo(-1)
    }
}
