package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

class AlarmSessionReducerTest {

    private val occurrence: Instant = Instant.parse("2026-08-12T04:00:00Z")

    private fun config(repeatCount: Int, intervalMinutes: Long = 5) = SessionConfig(
        snoozeInterval = Duration.ofMinutes(intervalMinutes),
        snoozeRepeatCount = repeatCount,
    )

    private fun scheduled() = SessionState(
        alarmId = 1,
        occurrence = occurrence,
        phase = SessionPhase.SCHEDULED,
        nextActionAt = occurrence,
    )

    private fun reduce(state: SessionState, config: SessionConfig, event: SessionEvent) =
        AlarmSessionReducer.reduce(state, config, event)

    // --- The zero-interaction chain ---

    @Test
    fun `full auto chain with snooze count exhausts and auto-dismisses`() {
        val config = config(repeatCount = 2)
        var now = occurrence
        var (state, effects) = reduce(scheduled(), config, SessionEvent.Fire(now))

        assertThat(state.phase).isEqualTo(SessionPhase.FIRING)
        assertThat(effects).containsExactly(
            SessionEffect.Persist(state),
            SessionEffect.ShowFiringNotification,
            SessionEffect.StartOutputs,
        ).inOrder()

        repeat(2) { round ->
            now = now.plusSeconds(30)
            val (snoozed, snoozeEffects) = reduce(state, config, SessionEvent.PlaybackComplete(now))
            assertThat(snoozed.phase).isEqualTo(SessionPhase.SNOOZED)
            assertThat(snoozed.snoozesUsed).isEqualTo(round + 1)
            assertThat(snoozed.nextActionAt).isEqualTo(now.plus(Duration.ofMinutes(5)))
            assertThat(snoozeEffects).contains(SessionEffect.ArmExact(snoozed.nextActionAt))

            now = snoozed.nextActionAt
            val (firing, _) = reduce(snoozed, config, SessionEvent.Fire(now))
            assertThat(firing.phase).isEqualTo(SessionPhase.FIRING)
            state = firing
        }

        // Snooze budget (2) is used up — this playback end finishes the chain silently.
        val endedAt = now.plusSeconds(30)
        val (done, doneEffects) = reduce(state, config, SessionEvent.PlaybackComplete(endedAt))
        assertThat(done.phase).isEqualTo(SessionPhase.DONE)
        assertThat(done.endedReason).isEqualTo(EndReason.AUTO_DISMISSED)
        assertThat(doneEffects).containsExactly(
            SessionEffect.StopOutputs,
            SessionEffect.Persist(done),
            SessionEffect.CancelNotifications,
            SessionEffect.ScheduleNextOccurrence,
            // Three rings, none of them switched off by hand: the morning after should
            // say so rather than look like a morning the alarm never went off.
            SessionEffect.ReportUnattended(
                firstRingAt = occurrence,
                endedAt = endedAt,
                ringCount = 3,
            ),
        ).inOrder()
    }

    // --- "It rang, and you never switched it off" ---

    /**
     * Order matters more than it looks: CancelNotifications clears this alarm's notices,
     * so a notice reported before it would be posted and immediately wiped.
     */
    @Test
    fun `the unattended report comes after the notifications are cancelled`() {
        val config = config(repeatCount = 0)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (_, effects) = reduce(firing, config, SessionEvent.PlaybackComplete(occurrence))

        val cancelAt = effects.indexOf(SessionEffect.CancelNotifications)
        val reportAt = effects.indexOfFirst { it is SessionEffect.ReportUnattended }
        assertThat(cancelAt).isGreaterThan(-1)
        assertThat(reportAt).isGreaterThan(cancelAt)
    }

    @Test
    fun `a single ring that nobody stopped is reported as one ring`() {
        val config = config(repeatCount = 0)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val endedAt = occurrence.plusSeconds(12)
        val (_, effects) = reduce(firing, config, SessionEvent.PlaybackComplete(endedAt))

        assertThat(effects.filterIsInstance<SessionEffect.ReportUnattended>().single())
            .isEqualTo(
                SessionEffect.ReportUnattended(
                    firstRingAt = occurrence,
                    endedAt = endedAt,
                    ringCount = 1,
                ),
            )
    }

    /** The criterion is "you did not switch it off", not "you never touched the phone". */
    @Test
    fun `snoozing by hand and then sleeping through still counts as unattended`() {
        val config = config(repeatCount = 1)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (snoozed, _) = reduce(firing, config, SessionEvent.UserSnooze(occurrence))
        val (refire, _) = reduce(snoozed, config, SessionEvent.Fire(snoozed.nextActionAt))
        val (_, effects) = reduce(refire, config, SessionEvent.PlaybackComplete(snoozed.nextActionAt))

        assertThat(effects.filterIsInstance<SessionEffect.ReportUnattended>().single().ringCount)
            .isEqualTo(2)
    }

    /** Switching it off yourself is the one case that needs no reminder. */
    @Test
    fun `a dismissed alarm is never reported as unattended`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))

        val (_, dismissedWhileFiring) = reduce(firing, config, SessionEvent.UserDismiss(occurrence))
        assertThat(dismissedWhileFiring.filterIsInstance<SessionEffect.ReportUnattended>()).isEmpty()

        val (snoozed, _) = reduce(firing, config, SessionEvent.PlaybackComplete(occurrence))
        val (_, dismissedWhileSnoozed) = reduce(snoozed, config, SessionEvent.UserDismiss(occurrence))
        assertThat(dismissedWhileSnoozed.filterIsInstance<SessionEffect.ReportUnattended>()).isEmpty()
    }

    /** Preemption already reports itself as missed; two notices for one alarm is noise. */
    @Test
    fun `a preempted alarm reports missed and not unattended`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (_, effects) = reduce(firing, config, SessionEvent.Preempted(occurrence))

        assertThat(effects).contains(SessionEffect.ReportMissed(occurrence))
        assertThat(effects.filterIsInstance<SessionEffect.ReportUnattended>()).isEmpty()
    }

    /** An "until dismissed" chain never ends by itself, so there is nothing to report. */
    @Test
    fun `infinite snooze never reports unattended`() {
        val config = config(repeatCount = -1)
        var state = scheduled()
        var now = occurrence
        repeat(5) {
            val (firing, _) = reduce(state, config, SessionEvent.Fire(now))
            val (snoozed, effects) = reduce(firing, config, SessionEvent.PlaybackComplete(now))
            assertThat(effects.filterIsInstance<SessionEffect.ReportUnattended>()).isEmpty()
            state = snoozed
            now = snoozed.nextActionAt
        }
    }

    @Test
    fun `repeat count 1 rings twice total`() {
        val config = config(repeatCount = 1)
        var now = occurrence
        var (state, _) = reduce(scheduled(), config, SessionEvent.Fire(now))

        val (snoozed, _) = reduce(state, config, SessionEvent.PlaybackComplete(now))
        assertThat(snoozed.phase).isEqualTo(SessionPhase.SNOOZED)

        now = snoozed.nextActionAt
        val (firing, _) = reduce(snoozed, config, SessionEvent.Fire(now))
        val (done, _) = reduce(firing, config, SessionEvent.PlaybackComplete(now))
        assertThat(done.phase).isEqualTo(SessionPhase.DONE)
        assertThat(done.endedReason).isEqualTo(EndReason.AUTO_DISMISSED)
    }

    @Test
    fun `infinite snooze never auto-dismisses`() {
        val config = config(repeatCount = -1)
        var now = occurrence
        var state = scheduled()

        repeat(10) {
            val (firing, _) = reduce(state, config, SessionEvent.Fire(now))
            val (snoozed, effects) = reduce(firing, config, SessionEvent.PlaybackComplete(now))
            assertThat(snoozed.phase).isEqualTo(SessionPhase.SNOOZED)
            val notification = effects.filterIsInstance<SessionEffect.ShowSnoozedNotification>().single()
            assertThat(notification.remainingSnoozes).isNull()
            state = snoozed
            now = snoozed.nextActionAt
        }
        assertThat(state.snoozesUsed).isEqualTo(10)
    }

    // --- User interaction ---

    @Test
    fun `user snooze consumes a snooze slot`() {
        val config = config(repeatCount = 1)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (snoozed, _) = reduce(firing, config, SessionEvent.UserSnooze(occurrence))
        assertThat(snoozed.snoozesUsed).isEqualTo(1)

        val (refire, _) = reduce(snoozed, config, SessionEvent.Fire(snoozed.nextActionAt))
        val (done, _) = reduce(refire, config, SessionEvent.PlaybackComplete(snoozed.nextActionAt))
        assertThat(done.endedReason).isEqualTo(EndReason.AUTO_DISMISSED)
    }

    /**
     * "No snooze" turns off the *automatic* chain, not the button. The interval is hidden
     * from the editor in that state, and this is what it is still doing: one snooze if the
     * user asks for it by hand, then the chain ends.
     */
    @Test
    fun `with no automatic repeats a hand-pressed snooze still works once`() {
        val config = config(repeatCount = 0, intervalMinutes = 5)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))

        val (snoozed, effects) = reduce(firing, config, SessionEvent.UserSnooze(occurrence))
        assertThat(snoozed.phase).isEqualTo(SessionPhase.SNOOZED)
        assertThat(snoozed.nextActionAt).isEqualTo(occurrence.plus(Duration.ofMinutes(5)))
        assertThat(effects).contains(SessionEffect.ArmExact(snoozed.nextActionAt))

        // And it is exactly one: the ring after it ends the chain.
        val (refire, _) = reduce(snoozed, config, SessionEvent.Fire(snoozed.nextActionAt))
        val (done, _) = reduce(refire, config, SessionEvent.PlaybackComplete(snoozed.nextActionAt))
        assertThat(done.phase).isEqualTo(SessionPhase.DONE)
        assertThat(done.endedReason).isEqualTo(EndReason.AUTO_DISMISSED)
    }

    @Test
    fun `user dismiss while firing stops outputs and schedules next`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (done, effects) = reduce(firing, config, SessionEvent.UserDismiss(occurrence))
        assertThat(done.phase).isEqualTo(SessionPhase.DONE)
        assertThat(done.endedReason).isEqualTo(EndReason.USER_DISMISSED)
        assertThat(effects).contains(SessionEffect.StopOutputs)
        assertThat(effects).contains(SessionEffect.ScheduleNextOccurrence)
    }

    @Test
    fun `user dismiss while snoozed ends the chain`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (snoozed, _) = reduce(firing, config, SessionEvent.PlaybackComplete(occurrence))
        val (done, _) = reduce(snoozed, config, SessionEvent.UserDismiss(occurrence))
        assertThat(done.phase).isEqualTo(SessionPhase.DONE)
        assertThat(done.endedReason).isEqualTo(EndReason.USER_DISMISSED)
    }

    // --- Recovery ---

    @Test
    fun `resume with future trigger only re-arms`() {
        val config = config(repeatCount = 3)
        val state = scheduled()
        val now = occurrence.minusSeconds(3600)
        val (next, effects) = reduce(state, config, SessionEvent.Resume(now))
        assertThat(next).isEqualTo(state)
        assertThat(effects).containsExactly(SessionEffect.ArmExact(occurrence))
    }

    @Test
    fun `resume snoozed past trigger re-fires after grace`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (snoozed, _) = reduce(firing, config, SessionEvent.PlaybackComplete(occurrence))

        // Phone was off through the snooze-until moment and well past the missed
        // window — a snoozed chain still resumes (it already woke the user once).
        val now = snoozed.nextActionAt.plus(Duration.ofHours(2))
        val (resumed, effects) = reduce(snoozed, config, SessionEvent.Resume(now))
        assertThat(resumed.phase).isEqualTo(SessionPhase.SNOOZED)
        assertThat(resumed.nextActionAt).isEqualTo(now.plus(config.refireGrace))
        assertThat(effects).containsExactly(
            SessionEffect.Persist(resumed),
            SessionEffect.ArmExact(resumed.nextActionAt),
        ).inOrder()
    }

    @Test
    fun `resume scheduled trigger missed within window fires late`() {
        val config = config(repeatCount = 3)
        val state = scheduled()
        val now = occurrence.plus(Duration.ofMinutes(10))
        val (resumed, effects) = reduce(state, config, SessionEvent.Resume(now))
        assertThat(resumed.nextActionAt).isEqualTo(now.plus(config.refireGrace))
        assertThat(effects).contains(SessionEffect.ArmExact(resumed.nextActionAt))
    }

    @Test
    fun `resume scheduled trigger missed beyond window reports missed`() {
        val config = config(repeatCount = 3)
        val state = scheduled()
        val now = occurrence.plus(Duration.ofHours(2))
        val (resumed, effects) = reduce(state, config, SessionEvent.Resume(now))
        assertThat(resumed.phase).isEqualTo(SessionPhase.DONE)
        assertThat(resumed.endedReason).isEqualTo(EndReason.MISSED)
        assertThat(effects).contains(SessionEffect.ReportMissed(occurrence))
        assertThat(effects).contains(SessionEffect.ScheduleNextOccurrence)
    }

    @Test
    fun `resume mid-firing re-fires without consuming a snooze slot`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val now = occurrence.plusSeconds(10)
        val (resumed, effects) = reduce(firing, config, SessionEvent.Resume(now))
        assertThat(resumed.phase).isEqualTo(SessionPhase.SNOOZED)
        assertThat(resumed.snoozesUsed).isEqualTo(0)
        assertThat(effects).contains(SessionEffect.ArmExact(now.plus(config.refireGrace)))
    }

    // --- Preemption / terminal state ---

    @Test
    fun `preempted while firing ends chain and reports missed`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (done, effects) = reduce(firing, config, SessionEvent.Preempted(occurrence))
        assertThat(done.phase).isEqualTo(SessionPhase.DONE)
        assertThat(done.endedReason).isEqualTo(EndReason.PREEMPTED)
        assertThat(effects).contains(SessionEffect.StopOutputs)
        assertThat(effects).contains(SessionEffect.ReportMissed(occurrence))
    }

    @Test
    fun `done state ignores all events`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (done, _) = reduce(firing, config, SessionEvent.UserDismiss(occurrence))

        listOf(
            SessionEvent.Fire(occurrence),
            SessionEvent.PlaybackComplete(occurrence),
            SessionEvent.UserSnooze(occurrence),
            SessionEvent.UserDismiss(occurrence),
            SessionEvent.Resume(occurrence),
            SessionEvent.Preempted(occurrence),
        ).forEach { event ->
            val (state, effects) = reduce(done, config, event)
            assertThat(state).isEqualTo(done)
            assertThat(effects).isEmpty()
        }
    }

    @Test
    fun `fire while already firing is a no-op`() {
        val config = config(repeatCount = 3)
        val (firing, _) = reduce(scheduled(), config, SessionEvent.Fire(occurrence))
        val (state, effects) = reduce(firing, config, SessionEvent.Fire(occurrence.plusSeconds(1)))
        assertThat(state).isEqualTo(firing)
        assertThat(effects).isEmpty()
    }

    @Test
    fun `persist is always emitted before arm`() {
        val config = config(repeatCount = 2)
        var now = occurrence
        var state = scheduled()
        val events = listOf(
            SessionEvent.Fire(now),
            SessionEvent.PlaybackComplete(now),
            SessionEvent.Fire(now.plus(Duration.ofMinutes(5))),
            SessionEvent.UserSnooze(now.plus(Duration.ofMinutes(5))),
        )
        for (event in events) {
            val (next, effects) = reduce(state, config, event)
            val persistIndex = effects.indexOfFirst { it is SessionEffect.Persist }
            val armIndex = effects.indexOfFirst { it is SessionEffect.ArmExact }
            if (armIndex >= 0) {
                assertThat(persistIndex).isAtLeast(0)
                assertThat(persistIndex).isLessThan(armIndex)
            }
            state = next
        }
    }
}
