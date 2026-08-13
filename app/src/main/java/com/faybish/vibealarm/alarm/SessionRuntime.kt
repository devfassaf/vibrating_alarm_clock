package com.faybish.vibealarm.alarm

import android.content.Context
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmInstanceEntity
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.EndedReason
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.domain.AlarmSessionReducer
import com.faybish.vibealarm.domain.EndReason
import com.faybish.vibealarm.domain.SessionConfig
import com.faybish.vibealarm.domain.SessionEffect
import com.faybish.vibealarm.domain.SessionEvent
import com.faybish.vibealarm.domain.SessionPhase
import com.faybish.vibealarm.domain.SessionState
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.withLock

/**
 * Where the pure [AlarmSessionReducer] meets Android: loads persisted state,
 * runs a transition, then executes the resulting effects in order.
 *
 * Effects that produce vibration or sound are delegated to an [OutputSink] so
 * the same code path works whether the caller is the ringing service (which
 * drives the engines directly) or a receiver (which starts/stops the service).
 */
class SessionRuntime(
    private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val notifications: AlarmNotifications,
    private val logger: ReliabilityLogger,
) {

    /** Sink for the effects that need a live vibrator/player. */
    interface OutputSink {
        fun startOutputs(alarm: AlarmEntity, instanceId: Long)

        /** @param alarmId whose outputs to stop — never silence a different alarm. */
        fun stopOutputs(alarmId: Long)

        /** Promote the firing notification (the service's own foreground notification). */
        fun showFiring(alarm: AlarmEntity, instanceId: Long)
    }

    /** Used by receivers and activities: control the service instead of the engines. */
    inner class ServiceControlSink : OutputSink {
        override fun startOutputs(alarm: AlarmEntity, instanceId: Long) {
            AlarmServiceStarter.start(context, alarm, instanceId, logger, notifications)
        }

        override fun stopOutputs(alarmId: Long) = AlarmServiceStarter.stop(context, alarmId)

        override fun showFiring(alarm: AlarmEntity, instanceId: Long) {
            // The service posts its own foreground notification when it starts.
        }
    }

    /**
     * Runs one transition for the alarm's active chain.
     *
     * @param instanceIdHint the instance id carried by the PendingIntent; used to
     *   recover when bookkeeping and reality disagree — an alarm must ring even if
     *   its instance row is missing.
     */
    suspend fun handle(
        alarmId: Long,
        event: SessionEvent,
        sink: OutputSink,
        instanceIdHint: Long = 0,
    ): SessionState? = scheduler.mutex.withLock {
        handleLocked(alarmId, event, sink, instanceIdHint)
    }

    /**
     * Assumes [AlarmScheduler.mutex] is held. Every transition reads the instance row
     * and writes it back whole, so two running at once would clobber each other — and
     * a Resume landing on a live ring would demote it back to snoozed.
     */
    internal suspend fun handleLocked(
        alarmId: Long,
        event: SessionEvent,
        sink: OutputSink,
        instanceIdHint: Long,
    ): SessionState? {
        val alarm = repository.getAlarm(alarmId) ?: run {
            logger.log(ReliabilityLogger.MISSED, "alarm=$alarmId no longer exists")
            scheduler.cancel(alarmId)
            return null
        }

        var entity = resolveInstance(alarm, event, instanceIdHint) ?: return null
        val state = entity.toState()
        val config = alarm.toSessionConfig()

        val (next, effects) = AlarmSessionReducer.reduce(state, config, event)
        if (effects.isEmpty()) return next

        for (effect in effects) {
            // One failing effect must not abandon the rest — losing ArmExact would
            // end the chain silently, which is the one outcome this app cannot have.
            try {
                entity = apply(effect, alarm, entity, next, sink)
            } catch (e: Exception) {
                logger.log(
                    ReliabilityLogger.EFFECT_FAILED,
                    "alarm=$alarmId effect=${effect.javaClass.simpleName} " +
                        "${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
        return next
    }

    /** @return the instance row, updated if this effect persisted it. */
    private suspend fun apply(
        effect: SessionEffect,
        alarm: AlarmEntity,
        entity: AlarmInstanceEntity,
        next: SessionState,
        sink: OutputSink,
    ): AlarmInstanceEntity {
        when (effect) {
            is SessionEffect.Persist -> {
                val updated = entity.applying(effect.state)
                val id = repository.saveInstance(updated)
                return updated.copy(id = id)
            }

            SessionEffect.StartOutputs -> sink.startOutputs(alarm, entity.id)

            SessionEffect.StopOutputs -> sink.stopOutputs(alarm.id)

            is SessionEffect.ArmExact -> scheduler.arm(alarm.id, entity.id, effect.at)

            SessionEffect.ShowFiringNotification -> {
                logger.log(
                    ReliabilityLogger.FIRED,
                    "alarm=${alarm.id} instance=${entity.id} snoozesUsed=${next.snoozesUsed}",
                )
                // Last night's "you never switched it off" notice must not sit next to
                // tonight's alarm as if it were about this one — and neither must the
                // "snoozed until 07:35" notice from the ring this one is the sequel to,
                // which otherwise sits in the shade with the same title as the live alarm.
                notifications.cancelUnattended(alarm.id)
                notifications.cancelSnoozed(alarm.id)
                sink.showFiring(alarm, entity.id)
            }

            is SessionEffect.ShowSnoozedNotification -> {
                logger.log(
                    ReliabilityLogger.SNOOZED,
                    "alarm=${alarm.id} until=${effect.until} remaining=${effect.remainingSnoozes}",
                )
                notifications.showSnoozed(alarm, entity.id, effect.until, effect.remainingSnoozes)
            }

            SessionEffect.CancelNotifications -> {
                next.endedReason?.let {
                    logger.log(it.logEvent(), "alarm=${alarm.id} instance=${entity.id}")
                }
                notifications.cancelForAlarm(alarm.id)
            }

            SessionEffect.ScheduleNextOccurrence ->
                scheduler.scheduleNextOccurrenceLocked(alarm, afterFiring = true)

            is SessionEffect.ReportMissed -> {
                logger.log(ReliabilityLogger.MISSED, "alarm=${alarm.id} at=${effect.occurrence}")
                notifications.showMissed(alarm, effect.occurrence)
            }

            is SessionEffect.ReportUnattended -> {
                logger.log(
                    ReliabilityLogger.UNATTENDED,
                    "alarm=${alarm.id} rings=${effect.ringCount} " +
                        "from=${effect.firstRingAt} to=${effect.endedAt}",
                )
                notifications.showUnattended(
                    alarm = alarm,
                    firstRingAt = effect.firstRingAt,
                    endedAt = effect.endedAt,
                    ringCount = effect.ringCount,
                )
            }
        }
        return entity
    }

    /**
     * Re-arms or re-fires every chain that was mid-flight, e.g. after a reboot
     * (including one that happened before the user unlocked the phone).
     */
    suspend fun resumeAll() = scheduler.mutex.withLock { resumeAllLocked() }

    /** Assumes [AlarmScheduler.mutex] is held. */
    internal suspend fun resumeAllLocked() {
        val now = Instant.now()
        val sink = ServiceControlSink()
        for (instance in repository.allActiveInstances()) {
            handleLocked(instance.alarmId, SessionEvent.Resume(now), sink, instanceIdHint = instance.id)
        }
    }

    /**
     * Ends any other alarm's active chain so a single session is alerting at a
     * time (there is only one vibrator). Mirrors Google Clock's behavior.
     */
    suspend fun preemptOthers(exceptAlarmId: Long, sink: OutputSink) = scheduler.mutex.withLock {
        val now = Instant.now()
        for (instance in repository.allActiveInstances()) {
            if (instance.alarmId == exceptAlarmId) continue
            if (instance.state != InstanceState.FIRING) continue
            handleLocked(instance.alarmId, SessionEvent.Preempted(now), sink, instanceIdHint = instance.id)
        }
    }

    private suspend fun resolveInstance(
        alarm: AlarmEntity,
        event: SessionEvent,
        instanceIdHint: Long,
    ): AlarmInstanceEntity? {
        repository.activeInstance(alarm.id)?.let { return it }

        val hinted = instanceIdHint.takeIf { it > 0 }?.let { repository.getInstance(it) }
        if (hinted != null && hinted.state != InstanceState.DONE) return hinted

        // The trigger arrived but no live chain exists (row pruned, or the alarm was
        // edited between arming and firing). Ringing beats bookkeeping: synthesize a
        // chain for right now. For non-fire events there is nothing to act on.
        if (event !is SessionEvent.Fire) return null

        val now = Instant.now().toEpochMilli()
        logger.log(ReliabilityLogger.FIRED, "alarm=${alarm.id} recovered missing instance")
        val id = repository.saveInstance(
            AlarmInstanceEntity(
                alarmId = alarm.id,
                occurrenceEpochMillis = now,
                state = InstanceState.SCHEDULED,
                nextActionEpochMillis = now,
            ),
        )
        return repository.getInstance(id)
    }
}

internal fun AlarmEntity.toSessionConfig() = SessionConfig(
    snoozeInterval = Duration.ofMinutes(snoozeIntervalMinutes.toLong()),
    snoozeRepeatCount = snoozeRepeatCount,
)

internal fun AlarmInstanceEntity.toState() = SessionState(
    alarmId = alarmId,
    occurrence = Instant.ofEpochMilli(occurrenceEpochMillis),
    phase = when (state) {
        InstanceState.FIRING -> SessionPhase.FIRING
        InstanceState.SNOOZED -> SessionPhase.SNOOZED
        InstanceState.DONE -> SessionPhase.DONE
        else -> SessionPhase.SCHEDULED
    },
    snoozesUsed = snoozesUsed,
    nextActionAt = Instant.ofEpochMilli(nextActionEpochMillis),
    endedReason = when (endedReason) {
        EndedReason.AUTO_DISMISSED -> EndReason.AUTO_DISMISSED
        EndedReason.USER_DISMISSED -> EndReason.USER_DISMISSED
        EndedReason.MISSED -> EndReason.MISSED
        EndedReason.PREEMPTED -> EndReason.PREEMPTED
        else -> null
    },
)

internal fun AlarmInstanceEntity.applying(state: SessionState) = copy(
    state = when (state.phase) {
        SessionPhase.SCHEDULED -> InstanceState.SCHEDULED
        SessionPhase.FIRING -> InstanceState.FIRING
        SessionPhase.SNOOZED -> InstanceState.SNOOZED
        SessionPhase.DONE -> InstanceState.DONE
    },
    snoozesUsed = state.snoozesUsed,
    nextActionEpochMillis = state.nextActionAt.toEpochMilli(),
    firedAt = if (state.phase == SessionPhase.FIRING) System.currentTimeMillis() else firedAt,
    endedReason = when (state.endedReason) {
        EndReason.AUTO_DISMISSED -> EndedReason.AUTO_DISMISSED
        EndReason.USER_DISMISSED -> EndedReason.USER_DISMISSED
        EndReason.MISSED -> EndedReason.MISSED
        EndReason.PREEMPTED -> EndedReason.PREEMPTED
        null -> endedReason
    },
)

private fun EndReason.logEvent(): String = when (this) {
    EndReason.AUTO_DISMISSED -> ReliabilityLogger.AUTO_DISMISSED
    EndReason.USER_DISMISSED -> ReliabilityLogger.USER_DISMISSED
    EndReason.MISSED -> ReliabilityLogger.MISSED
    EndReason.PREEMPTED -> ReliabilityLogger.PREEMPTED
}
