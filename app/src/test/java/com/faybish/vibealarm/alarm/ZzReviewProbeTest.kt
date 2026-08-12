package com.faybish.vibealarm.alarm

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.domain.SessionEvent
import com.google.common.truth.Truth.assertThat
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** TEMPORARY review probe. Deleted after the review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZzReviewProbeTest {

    private lateinit var context: Context
    private lateinit var db: AppDb
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var runtime: SessionRuntime

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")

    private class RecordingSink(val name: String) : SessionRuntime.OutputSink {
        val started = mutableListOf<Long>()
        val firingShown = mutableListOf<Long>()
        var stopCount = 0
        override fun startOutputs(alarm: AlarmEntity, instanceId: Long) { started += alarm.id }
        override fun stopOutputs() { stopCount++ }
        override fun showFiring(alarm: AlarmEntity, instanceId: Long) { firingShown += alarm.id }
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
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun createAlarm(): AlarmEntity {
        val a = AlarmEntity(
            label = "probe",
            enabled = true,
            timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(7, 0)),
            mode = RingMode.VIBRATE_ONLY,
            turnScreenOn = false,
            snoozeRepeatCount = 3,
            snoozeIntervalMinutes = 5,
        )
        val id = repository.saveAlarm(a)
        val saved = repository.getAlarm(id)!!
        scheduler.onAlarmSaved(saved)
        return saved
    }

    /**
     * PROBE 1 — reproduces the real AlarmReceiver -> AlarmRingingService sequence:
     * the receiver runs Fire with ServiceControlSink (which only launches the service),
     * then the service runs Fire again with its own sink (which drives the vibrator).
     */
    @Test
    fun probe1_service_side_fire_never_starts_outputs() = runTest {
        val alarm = createAlarm()
        val instanceId = repository.activeInstance(alarm.id)!!.id

        // Step 1: AlarmReceiver.onReceive -> handle(Fire, ServiceControlSink)
        val receiverSink = RecordingSink("receiver")
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), receiverSink, instanceId)

        // ServiceControlSink.startOutputs starts the service (real ServiceControlSink's
        // showFiring is a no-op; this fake records it, which is why we don't assert on it).
        println("PROBE1 receiverSink.started=${receiverSink.started}")
        assertThat(receiverSink.started).containsExactly(alarm.id)
        assertThat(repository.activeInstance(alarm.id)!!.state).isEqualTo(InstanceState.FIRING)

        // Step 2: AlarmRingingService.onStartCommand -> handle(Fire, service sink)
        val serviceSink = RecordingSink("service")
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), serviceSink, instanceId)

        println("PROBE1 serviceSink.started=${serviceSink.started} firingShown=${serviceSink.firingShown}")
        // If these are empty, startPlayback() and postFiring() never run: no vibration,
        // no action buttons, no full-screen intent, no PlaybackComplete timer.
        assertThat(serviceSink.started).isEmpty()
        assertThat(serviceSink.firingShown).isEmpty()
    }

    /**
     * PROBE 2 — AppGraph.init's unconditional armAll() racing the Fire it was cold-started
     * for: Resume arriving after Fire rewrites FIRING to SNOOZED, and the subsequent
     * PlaybackComplete is then dropped by the reducer.
     */
    @Test
    fun probe2_resume_after_fire_clobbers_firing_and_drops_playback_complete() = runTest {
        val alarm = createAlarm()
        val instanceId = repository.activeInstance(alarm.id)!!.id
        val sink = RecordingSink("service")

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        assertThat(repository.activeInstance(alarm.id)!!.state).isEqualTo(InstanceState.FIRING)

        // AppGraph.init { armAll -> resumeAll } lands a moment later.
        runtime.handle(alarm.id, SessionEvent.Resume(Instant.now()), sink, instanceId)
        val after = repository.activeInstance(alarm.id)!!
        println("PROBE2 after resume state=${after.state} snoozesUsed=${after.snoozesUsed}")
        assertThat(after.state).isEqualTo(InstanceState.SNOOZED)

        // The vibrator is still running (StopOutputs was never emitted by Resume)...
        assertThat(sink.stopCount).isEqualTo(0)

        // ...and the window-end transition is now a no-op: nothing stops output,
        // nothing arms, snoozesUsed is not incremented.
        val stopsBefore = sink.stopCount
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)
        val end = repository.activeInstance(alarm.id)!!
        println("PROBE2 after playbackComplete state=${end.state} snoozesUsed=${end.snoozesUsed} stops=${sink.stopCount}")
        assertThat(sink.stopCount).isEqualTo(stopsBefore)
        assertThat(end.snoozesUsed).isEqualTo(0)
    }
}
