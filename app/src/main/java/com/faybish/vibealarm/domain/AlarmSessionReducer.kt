package com.faybish.vibealarm.domain

import java.time.Instant

/**
 * The heart of the app: a pure state machine for one alarm occurrence.
 *
 * The zero-interaction ("Shabbat") flow emerges from the transitions:
 * Fire -> FIRING (outputs play ONCE) -> PlaybackComplete -> SNOOZED (auto) ->
 * Fire -> ... until the snooze budget is exhausted -> DONE(AUTO_DISMISSED) ->
 * ScheduleNextOccurrence. No touch required at any point.
 */
object AlarmSessionReducer {

    fun reduce(
        state: SessionState,
        config: SessionConfig,
        event: SessionEvent,
    ): Pair<SessionState, List<SessionEffect>> {
        if (state.phase == SessionPhase.DONE) return state to emptyList()

        return when (event) {
            is SessionEvent.Fire -> fire(state)
            is SessionEvent.PlaybackComplete -> playbackComplete(state, config, event.now)
            is SessionEvent.UserSnooze -> userSnooze(state, config, event.now)
            is SessionEvent.UserDismiss -> userDismiss(state)
            is SessionEvent.Resume -> resume(state, config, event.now)
            is SessionEvent.Preempted -> preempted(state)
        }
    }

    private fun fire(state: SessionState): Pair<SessionState, List<SessionEffect>> {
        if (state.phase == SessionPhase.FIRING) return state to emptyList()
        val next = state.copy(phase = SessionPhase.FIRING)
        return next to listOf(
            SessionEffect.Persist(next),
            SessionEffect.ShowFiringNotification,
            SessionEffect.StartOutputs,
        )
    }

    private fun playbackComplete(
        state: SessionState,
        config: SessionConfig,
        now: Instant,
    ): Pair<SessionState, List<SessionEffect>> {
        if (state.phase != SessionPhase.FIRING) return state to emptyList()
        val canAutoSnooze =
            config.snoozeRepeatCount == -1 || state.snoozesUsed < config.snoozeRepeatCount
        return if (canAutoSnooze) snooze(state, config, now) else finish(state, EndReason.AUTO_DISMISSED)
    }

    private fun userSnooze(
        state: SessionState,
        config: SessionConfig,
        now: Instant,
    ): Pair<SessionState, List<SessionEffect>> {
        if (state.phase != SessionPhase.FIRING) return state to emptyList()
        return snooze(state, config, now)
    }

    private fun snooze(
        state: SessionState,
        config: SessionConfig,
        now: Instant,
    ): Pair<SessionState, List<SessionEffect>> {
        val until = now.plus(config.snoozeInterval)
        val used = state.snoozesUsed + 1
        val next = state.copy(phase = SessionPhase.SNOOZED, snoozesUsed = used, nextActionAt = until)
        val remaining = if (config.snoozeRepeatCount == -1) {
            null
        } else {
            (config.snoozeRepeatCount - used).coerceAtLeast(0)
        }
        return next to listOf(
            SessionEffect.StopOutputs,
            SessionEffect.Persist(next),
            SessionEffect.ArmExact(until),
            SessionEffect.ShowSnoozedNotification(until, remaining),
        )
    }

    private fun userDismiss(state: SessionState): Pair<SessionState, List<SessionEffect>> =
        finish(state, EndReason.USER_DISMISSED)

    private fun preempted(state: SessionState): Pair<SessionState, List<SessionEffect>> {
        val (next, effects) = finish(state, EndReason.PREEMPTED)
        return next to effects + SessionEffect.ReportMissed(state.occurrence)
    }

    private fun finish(
        state: SessionState,
        reason: EndReason,
    ): Pair<SessionState, List<SessionEffect>> {
        val next = state.copy(phase = SessionPhase.DONE, endedReason = reason)
        return next to listOf(
            SessionEffect.StopOutputs,
            SessionEffect.Persist(next),
            SessionEffect.CancelNotifications,
            SessionEffect.ScheduleNextOccurrence,
        )
    }

    /**
     * Crash/reboot recovery. FIRING means we died mid-ring: re-fire shortly without
     * consuming a snooze slot. SCHEDULED/SNOOZED with a future trigger just re-arm;
     * with a recently-passed trigger they fire late; long-passed SCHEDULED triggers
     * are reported missed, while a SNOOZED chain (already woke the user once)
     * simply re-fires late and continues.
     */
    private fun resume(
        state: SessionState,
        config: SessionConfig,
        now: Instant,
    ): Pair<SessionState, List<SessionEffect>> {
        val refireAt = now.plus(config.refireGrace)

        return when (state.phase) {
            SessionPhase.FIRING -> {
                val next = state.copy(phase = SessionPhase.SNOOZED, nextActionAt = refireAt)
                next to listOf(SessionEffect.Persist(next), SessionEffect.ArmExact(refireAt))
            }

            SessionPhase.SCHEDULED, SessionPhase.SNOOZED -> {
                val trigger = state.nextActionAt
                when {
                    trigger.isAfter(now) ->
                        state to listOf(SessionEffect.ArmExact(trigger))

                    state.phase == SessionPhase.SCHEDULED &&
                        trigger.plus(config.missedWindow).isBefore(now) -> {
                        val next = state.copy(phase = SessionPhase.DONE, endedReason = EndReason.MISSED)
                        next to listOf(
                            SessionEffect.Persist(next),
                            SessionEffect.ReportMissed(state.occurrence),
                            SessionEffect.ScheduleNextOccurrence,
                        )
                    }

                    else -> {
                        val next = state.copy(nextActionAt = refireAt)
                        next to listOf(SessionEffect.Persist(next), SessionEffect.ArmExact(refireAt))
                    }
                }
            }

            SessionPhase.DONE -> state to emptyList()
        }
    }
}
