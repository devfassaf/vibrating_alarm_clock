package com.faybish.vibealarm.alarm

import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmInstanceEntity
import com.faybish.vibealarm.data.EndedReason
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.applying
import com.faybish.vibealarm.domain.AlertSelection
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

/**
 * The alert switches must survive the trip through the alarm row, because the runtime
 * reads the columns — not the selection the screen showed.
 */
class AlertStorageTest {

    private val base = AlarmEntity(timeMinutesOfDay = 420)

    @Test
    fun `each selection produces columns the runtime interprets the same way`() {
        val cases = listOf(
            AlertSelection.VIBRATION_ONLY to (RingMode.VIBRATE_ONLY to true),
            AlertSelection(sound = true, vibration = false) to (RingMode.SOUND to false),
            AlertSelection(sound = true, vibration = true) to (RingMode.SOUND to true),
        )
        cases.forEach { (selection, expected) ->
            val saved = base.applying(selection)
            assertThat(saved.mode).isEqualTo(expected.first)
            assertThat(saved.vibrateWithSound).isEqualTo(expected.second)
        }
    }

    /** What AlarmRingingService actually branches on, spelled out. */
    @Test
    fun `the runtime vibrates exactly when the vibration switch is on`() {
        listOf(
            AlertSelection.VIBRATION_ONLY,
            AlertSelection(sound = true, vibration = false),
            AlertSelection(sound = true, vibration = true),
        ).forEach { selection ->
            val saved = base.applying(selection)
            val vibrateOnly = saved.mode == RingMode.VIBRATE_ONLY
            val runtimeVibrates = vibrateOnly || saved.vibrateWithSound
            assertThat(runtimeVibrates).isEqualTo(selection.vibration)
        }
    }

    @Test
    fun `the runtime plays sound exactly when the sound switch is on`() {
        listOf(
            AlertSelection.VIBRATION_ONLY,
            AlertSelection(sound = true, vibration = false),
            AlertSelection(sound = true, vibration = true),
        ).forEach { selection ->
            val saved = base.applying(selection)
            val runtimePlaysSound = saved.mode != RingMode.VIBRATE_ONLY
            assertThat(runtimePlaysSound).isEqualTo(selection.sound)
        }
    }

    /** An alarm saved by an earlier version must keep meaning what it meant. */
    @Test
    fun `alarms written before the switches existed read back unchanged`() {
        val legacyVibrateOnly = base.copy(mode = RingMode.VIBRATE_ONLY, vibrateWithSound = false)
        val restored = AlertSelection.fromStorage(
            soundMode = legacyVibrateOnly.mode == RingMode.SOUND,
            vibrateWithSound = legacyVibrateOnly.vibrateWithSound,
        )
        assertThat(restored).isEqualTo(AlertSelection.VIBRATION_ONLY)
        // Re-saving it does not change what the runtime will do.
        val resaved = legacyVibrateOnly.applying(restored)
        assertThat(resaved.mode).isEqualTo(RingMode.VIBRATE_ONLY)
    }
}
