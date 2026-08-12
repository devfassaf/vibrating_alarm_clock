package com.faybish.vibealarm.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.EndedReason
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.SessionEvent
import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * End-to-end wiring test for the alarm pipeline, on the JVM.
 *
 * The pure reducer is covered by [AlarmSessionReducerTest]; what this exercises is
 * everything around it that a unit test normally cannot see: that a saved alarm
 * really lands in AlarmManager, that the auto-snooze chain re-arms the same slot
 * rather than stacking triggers, that one alarm never cancels another's trigger,
 * and that a chain which ends silently leaves the next occurrence armed.
 *
 * Output is captured through a fake [SessionRuntime.OutputSink] because there is no
 * vibrator here — the engines themselves are the one part that still needs the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmPipelineTest {

    private lateinit var context: Context
    private lateinit var db: AppDb
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var runtime: SessionRuntime
    private lateinit var sink: RecordingSink

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    /** Records the effects that would otherwise reach the vibrator. */
    private class RecordingSink : SessionRuntime.OutputSink {
        val started = mutableListOf<Long>()
        val stopped = mutableListOf<Long>()
        val firingShown = mutableListOf<Long>()

        override fun startOutputs(alarm: AlarmEntity, instanceId: Long) {
            started += alarm.id
        }

        override fun stopOutputs(alarmId: Long) {
            stopped += alarmId
        }

        override fun showFiring(alarm: AlarmEntity, instanceId: Long) {
            firingShown += alarm.id
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
            .createDeviceProtectedStorageContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AlarmRepository(db)
        val logger = ReliabilityLogger(db.logDao(), CoroutineScope(Dispatchers.Unconfined))
        scheduler = AlarmScheduler(context, repository, logger) { zone }
        runtime = SessionRuntime(context, repository, scheduler, AlarmNotifications(context), logger)
        sink = RecordingSink()
        // Android 12+ gates exact alarms behind a permission, and the shadow starts
        // without it. Grant it here; `no exact alarm permission` covers the other case.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = db.close()

    private fun scheduledAlarms() = shadowOf(alarmManager).scheduledAlarms

    private suspend fun createAlarm(alarm: AlarmEntity): AlarmEntity {
        val id = repository.saveAlarm(alarm)
        val saved = repository.getAlarm(id)!!
        scheduler.onAlarmSaved(saved)
        return saved
    }

    private fun vibrateOnlyAlarm(
        time: LocalTime = LocalTime.of(7, 0),
        snoozeRepeatCount: Int = 2,
        snoozeIntervalMinutes: Int = 5,
    ) = AlarmEntity(
        label = "test",
        enabled = true,
        timeMinutesOfDay = ScheduleCodec.timeToMinutes(time),
        mode = RingMode.VIBRATE_ONLY,
        turnScreenOn = false,
        snoozeRepeatCount = snoozeRepeatCount,
        snoozeIntervalMinutes = snoozeIntervalMinutes,
    )

    // --- Arming ---

    @Test
    fun `saving an enabled alarm arms exactly one trigger at the next occurrence`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm())

        assertThat(scheduledAlarms()).hasSize(1)
        val instance = repository.activeInstance(alarm.id)!!
        assertThat(instance.state).isEqualTo(InstanceState.SCHEDULED)
        assertThat(scheduledAlarms().single().triggerAtTime)
            .isEqualTo(instance.nextActionEpochMillis)

        // The armed time must be the next 07:00 wall-clock in the configured zone.
        val armed = Instant.ofEpochMilli(instance.nextActionEpochMillis).atZone(zone)
        assertThat(armed.toLocalTime()).isEqualTo(LocalTime.of(7, 0))
        assertThat(armed.toInstant()).isGreaterThan(Instant.now())
    }

    @Test
    fun `disabling an alarm cancels its trigger`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm())
        assertThat(scheduledAlarms()).hasSize(1)

        scheduler.onAlarmToggled(alarm.id, enabled = false)

        assertThat(scheduledAlarms()).isEmpty()
        assertThat(repository.activeInstance(alarm.id)).isNull()
    }

    @Test
    fun `deleting an alarm cancels its trigger`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm())
        scheduler.onAlarmDeleted(alarm.id)

        assertThat(scheduledAlarms()).isEmpty()
        assertThat(repository.getAlarm(alarm.id)).isNull()
    }

    @Test
    fun `two alarms hold independent triggers`() = runTest {
        val first = createAlarm(vibrateOnlyAlarm(time = LocalTime.of(6, 30)))
        val second = createAlarm(vibrateOnlyAlarm(time = LocalTime.of(8, 15)))

        assertThat(scheduledAlarms()).hasSize(2)

        // Cancelling one must leave the other armed — the reason each alarm gets its
        // own PendingIntent instead of a single global "next alarm" slot.
        scheduler.onAlarmToggled(first.id, enabled = false)
        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(repository.activeInstance(second.id)).isNotNull()
    }

    @Test
    fun `re-saving an alarm replaces its trigger instead of stacking one`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(time = LocalTime.of(6, 0)))
        val moved = alarm.copy(timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(9, 45)))
        scheduler.onAlarmSaved(repository.getAlarm(repository.saveAlarm(moved))!!)

        assertThat(scheduledAlarms()).hasSize(1)
        val armed = Instant.ofEpochMilli(repository.activeInstance(alarm.id)!!.nextActionEpochMillis)
            .atZone(zone)
        assertThat(armed.toLocalTime()).isEqualTo(LocalTime.of(9, 45))
    }

    // --- The zero-interaction chain ---

    @Test
    fun `vibration-only alarm auto-snoozes then auto-dismisses without any interaction`() = runTest {
        // Daily, so there is a next occurrence to check once the chain ends.
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                vibrateOnlyAlarm(snoozeRepeatCount = 2, snoozeIntervalMinutes = 5),
            ),
        )
        val instanceId = repository.activeInstance(alarm.id)!!.id

        // Ring 1
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        assertThat(repository.activeInstance(alarm.id)!!.state).isEqualTo(InstanceState.FIRING)
        assertThat(sink.started).containsExactly(alarm.id)

        // Pattern finishes on its own -> auto-snooze, armed 5 minutes out.
        var now = Instant.now()
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(now), sink, instanceId)
        var instance = repository.activeInstance(alarm.id)!!
        assertThat(instance.state).isEqualTo(InstanceState.SNOOZED)
        assertThat(instance.snoozesUsed).isEqualTo(1)
        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(scheduledAlarms().single().triggerAtTime)
            .isEqualTo(instance.nextActionEpochMillis)
        assertThat(instance.nextActionEpochMillis - now.toEpochMilli())
            .isWithin(2_000L).of(5 * 60_000L)

        // Rings 2 and 3, both unattended.
        repeat(2) {
            now = Instant.ofEpochMilli(repository.activeInstance(alarm.id)!!.nextActionEpochMillis)
            runtime.handle(alarm.id, SessionEvent.Fire(now), sink, instanceId)
            assertThat(repository.activeInstance(alarm.id)!!.state).isEqualTo(InstanceState.FIRING)
            runtime.handle(alarm.id, SessionEvent.PlaybackComplete(now), sink, instanceId)
        }

        // Budget spent: the chain ends silently and the next occurrence is armed.
        val finished = db.instanceDao().getById(instanceId)!!
        assertThat(finished.state).isEqualTo(InstanceState.DONE)
        assertThat(finished.endedReason).isEqualTo(EndedReason.AUTO_DISMISSED)
        assertThat(finished.snoozesUsed).isEqualTo(2)

        val next = repository.activeInstance(alarm.id)!!
        assertThat(next.id).isNotEqualTo(instanceId)
        assertThat(next.state).isEqualTo(InstanceState.SCHEDULED)
        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(scheduledAlarms().single().triggerAtTime).isEqualTo(next.nextActionEpochMillis)
        // Every ring stopped its own output.
        assertThat(sink.stopped).hasSize(3)
    }

    @Test
    fun `zero snooze repeats rings once and ends`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 0))
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)

        val finished = db.instanceDao().getById(instanceId)!!
        assertThat(finished.state).isEqualTo(InstanceState.DONE)
        assertThat(finished.endedReason).isEqualTo(EndedReason.AUTO_DISMISSED)
        assertThat(finished.snoozesUsed).isEqualTo(0)
        // Nothing is left alerting, and no snooze was ever armed.
        assertThat(repository.activeInstance(alarm.id)).isNull()
    }

    @Test
    fun `a one-time alarm turns itself off after it has rung`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 0))
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)

        // The default schedule is one-time, so there is nothing left to arm.
        assertThat(repository.getAlarm(alarm.id)!!.enabled).isFalse()
        assertThat(scheduledAlarms()).isEmpty()
    }

    @Test
    fun `a weekly alarm keeps running after a chain ends`() = runTest {
        val weekly = ScheduleCodec.encode(
            Schedule.Weekly(
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                defaultTime = LocalTime.of(7, 0),
                overrides = mapOf(DayOfWeek.TUESDAY to LocalTime.of(8, 0)),
            ),
            vibrateOnlyAlarm(snoozeRepeatCount = 0),
        )
        val alarm = createAlarm(weekly)
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)

        assertThat(repository.getAlarm(alarm.id)!!.enabled).isTrue()
        val next = repository.activeInstance(alarm.id)!!
        val armed = Instant.ofEpochMilli(next.nextActionEpochMillis).atZone(zone)
        assertThat(armed.dayOfWeek).isIn(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY))
        assertThat(armed.toLocalTime()).isEqualTo(
            if (armed.dayOfWeek == DayOfWeek.TUESDAY) LocalTime.of(8, 0) else LocalTime.of(7, 0),
        )
        assertThat(scheduledAlarms()).hasSize(1)
    }

    // --- Recovery ---

    @Test
    fun `armAll re-arms every enabled alarm and drops disabled ones`() = runTest {
        val enabled = createAlarm(vibrateOnlyAlarm(time = LocalTime.of(6, 0)))
        val disabled = createAlarm(vibrateOnlyAlarm(time = LocalTime.of(7, 0)))
        scheduler.onAlarmToggled(disabled.id, enabled = false)

        // Simulate a reboot: AlarmManager forgets everything, the database does not.
        shadowOf(alarmManager).scheduledAlarms.toList().forEach { scheduler.cancel(enabled.id) }
        assertThat(scheduledAlarms()).isEmpty()

        scheduler.armAll(runtime)

        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(repository.activeInstance(enabled.id)).isNotNull()
        assertThat(repository.activeInstance(disabled.id)).isNull()
    }

    @Test
    fun `a snooze chain interrupted by a reboot resumes instead of being lost`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 3))
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)
        val snoozed = repository.activeInstance(alarm.id)!!
        assertThat(snoozed.state).isEqualTo(InstanceState.SNOOZED)

        scheduler.cancel(alarm.id)
        assertThat(scheduledAlarms()).isEmpty()

        scheduler.armAll(runtime)

        val resumed = repository.activeInstance(alarm.id)!!
        assertThat(resumed.state).isEqualTo(InstanceState.SNOOZED)
        assertThat(resumed.snoozesUsed).isEqualTo(1)
        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(scheduledAlarms().single().triggerAtTime)
            .isEqualTo(resumed.nextActionEpochMillis)
    }

    @Test
    fun `a trigger with no surviving instance still rings`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm())
        // The bookkeeping row is gone (pruned, or edited between arming and firing).
        repository.clearActiveInstance(alarm.id)

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceIdHint = 0)

        assertThat(sink.started).containsExactly(alarm.id)
        assertThat(repository.activeInstance(alarm.id)!!.state).isEqualTo(InstanceState.FIRING)
    }

    /**
     * Without the exact-alarm permission nothing may be armed, and the app must say so
     * in the reliability log rather than pretending the alarm is set.
     */
    @Test
    fun `no exact alarm permission means nothing is armed and it is recorded`() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(scheduler.canScheduleExactAlarms()).isFalse()

        createAlarm(vibrateOnlyAlarm())

        assertThat(scheduledAlarms()).isEmpty()
        assertThat(db.logDao().latest(ReliabilityLogger.EXACT_ALARM_BLOCKED)).isNotNull()
    }

    @Test
    fun `a user dismiss ends the chain and arms nothing for a one-time alarm`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 3))
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.UserDismiss(Instant.now()), sink, instanceId)

        val finished = db.instanceDao().getById(instanceId)!!
        assertThat(finished.state).isEqualTo(InstanceState.DONE)
        assertThat(finished.endedReason).isEqualTo(EndedReason.USER_DISMISSED)
        assertThat(repository.getAlarm(alarm.id)!!.enabled).isFalse()
        assertThat(scheduledAlarms()).isEmpty()
    }
}
