package com.faybish.vibealarm.domain

import java.time.Duration
import java.time.Instant

/** Lifecycle phase of one alarm occurrence (an "instance"). */
enum class SessionPhase { SCHEDULED, FIRING, SNOOZED, DONE }

enum class EndReason { AUTO_DISMISSED, USER_DISMISSED, MISSED, PREEMPTED }

/**
 * Persistent state of one occurrence's ring/snooze chain. Mirrors the
 * `instances` table row so the chain survives process death and reboots.
 */
data class SessionState(
    val alarmId: Long,
    val occurrence: Instant,
    val phase: SessionPhase,
    val snoozesUsed: Int = 0,
    /** The trigger currently armed with AlarmManager (occurrence or snooze-until). */
    val nextActionAt: Instant,
    val endedReason: EndReason? = null,
)

/** Per-alarm snooze behavior + fixed runtime policies. */
data class SessionConfig(
    val snoozeInterval: Duration,
    /** How many times the alarm re-rings after the first ring; -1 = until dismissed. */
    val snoozeRepeatCount: Int,
    /** Delay used when (re)firing "now" — gives AlarmManager a clean handoff. */
    val refireGrace: Duration = Duration.ofSeconds(5),
    /** A trigger missed by more than this (phone was off) is reported, not fired. */
    val missedWindow: Duration = Duration.ofMinutes(30),
)

sealed interface SessionEvent {
    /** The armed trigger went off (initial occurrence or a snooze re-fire). */
    data class Fire(val now: Instant) : SessionEvent

    /** Vibration pattern finished playing once / sound auto-silence elapsed. */
    data class PlaybackComplete(val now: Instant) : SessionEvent

    data class UserSnooze(val now: Instant) : SessionEvent
    data class UserDismiss(val now: Instant) : SessionEvent

    /** Process (re)started — reboot, package update, crash recovery. */
    data class Resume(val now: Instant) : SessionEvent

    /** Another alarm started firing; a single session may be active at a time. */
    data class Preempted(val now: Instant) : SessionEvent
}

/**
 * Side effects the runtime must execute, in list order. The reducer always
 * emits [Persist] before [ArmExact] so a crash between the two is recoverable
 * by re-running Resume.
 */
sealed interface SessionEffect {
    data class Persist(val state: SessionState) : SessionEffect
    data object StartOutputs : SessionEffect
    data object StopOutputs : SessionEffect
    data class ArmExact(val at: Instant) : SessionEffect
    data object ShowFiringNotification : SessionEffect
    data class ShowSnoozedNotification(val until: Instant, val remainingSnoozes: Int?) : SessionEffect
    data object CancelNotifications : SessionEffect
    /** Chain over — compute and arm the alarm's next occurrence (or disable it). */
    data object ScheduleNextOccurrence : SessionEffect
    data class ReportMissed(val occurrence: Instant) : SessionEffect
}
