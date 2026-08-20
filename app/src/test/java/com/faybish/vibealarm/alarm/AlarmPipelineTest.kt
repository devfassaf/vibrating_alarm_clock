package com.faybish.vibealarm.alarm

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.AlarmInstanceEntity
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.EndedReason
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.MissedNotice
import com.faybish.vibealarm.data.NoticeKind
import com.faybish.vibealarm.data.missedNotices
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.SessionEvent
import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private lateinit var notifications: AlarmNotifications

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
        notifications = AlarmNotifications(context).also { it.ensureChannels() }
        runtime = SessionRuntime(context, repository, scheduler, notifications, logger)
        sink = RecordingSink()
        // Android 12+ gates exact alarms behind a permission, and the shadow starts
        // without it. Grant it here; `no exact alarm permission` covers the other case.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() = db.close()

    private fun scheduledAlarms() = shadowOf(alarmManager).scheduledAlarms

    private fun postedTitles(): List<String?> =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .allNotifications
            .map { it.extras.getString(Notification.EXTRA_TITLE) }

    private fun unattendedNotice(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .allNotifications
            .firstOrNull {
                // The title now carries the time, so match on the part that identifies it.
                val title = it.extras.getString(Notification.EXTRA_TITLE).orEmpty()
                title.startsWith(
                    context.getString(R.string.notification_unattended_title, "").trim(),
                )
            }

    private suspend fun unreadNotices(): List<MissedNotice> =
        missedNotices(repository.unreadNotices(), repository.getAllAlarms())

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

    /**
     * The morning after, this notice is the only thing on the phone that says the alarm
     * worked. Before it existed, CancelNotifications wiped every trace at the end of the
     * chain, and a Shabbat morning that went perfectly looked exactly like one where the
     * alarm had failed.
     */
    @Test
    fun `a chain that ran itself out leaves a notice saying it was never dismissed`() = runTest {
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                vibrateOnlyAlarm(snoozeRepeatCount = 1, snoozeIntervalMinutes = 5),
            ),
        )
        val instanceId = repository.activeInstance(alarm.id)!!.id

        var now = Instant.now()
        runtime.handle(alarm.id, SessionEvent.Fire(now), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(now), sink, instanceId)
        now = Instant.ofEpochMilli(repository.activeInstance(alarm.id)!!.nextActionEpochMillis)
        runtime.handle(alarm.id, SessionEvent.Fire(now), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(now), sink, instanceId)

        assertThat(db.instanceDao().getById(instanceId)!!.endedReason)
            .isEqualTo(EndedReason.AUTO_DISMISSED)

        // Survived CancelNotifications, which runs earlier in the same transition.
        val notice = unattendedNotice()
        assertThat(notice).isNotNull()
        assertThat(notice!!.extras.getString(Notification.EXTRA_TEXT)).contains("2")

        // And the row behind it: what the banner reads, and what keeps the launcher's red
        // dot meaningful. Without the stored end time the banner cannot say "until 07:40".
        val row = db.instanceDao().getById(instanceId)!!
        assertThat(row.endedAt).isNotNull()
        assertThat(row.endedAt!!).isAtLeast(row.firedAt!!)
        assertThat(row.noticeAckAt).isNull()

        val unread = unreadNotices()
        assertThat(unread.map { it.instanceId }).containsExactly(instanceId)
        assertThat(unread.single().kind).isEqualTo(NoticeKind.UNATTENDED)
        assertThat(unread.single().ringCount).isEqualTo(2)
    }

    /**
     * The banner and the notification are two faces of one row, so retiring one without the
     * other leaves the user with either a dot nothing explains or a banner about a morning
     * two rings ago.
     */
    @Test
    fun `the next ring marks the earlier notice read, banner and notification together`() =
        runTest {
            val alarm = createAlarm(
                ScheduleCodec.encode(
                    Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                    vibrateOnlyAlarm(snoozeRepeatCount = 0),
                ),
            )
            val firstInstance = repository.activeInstance(alarm.id)!!.id

            runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, firstInstance)
            runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, firstInstance)
            assertThat(unreadNotices()).hasSize(1)

            val nextInstance = repository.activeInstance(alarm.id)!!.id
            runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, nextInstance)

            assertThat(db.instanceDao().getById(firstInstance)!!.noticeAckAt).isNotNull()
            assertThat(unreadNotices()).isEmpty()
            assertThat(unattendedNotice()).isNull()
        }

    /**
     * The guard CLAUDE.md #14 shouts about, exercised end to end: acknowledging on fire is
     * scoped to chains that already ended, because it runs from inside the ring. Without the
     * `state = 3` filter the ringing chain stamps its own row read before it has anything to
     * report, and the notice it produces at the end of this very test is born invisible.
     */
    @Test
    fun `the chain that clears an old notice still produces its own`() = runTest {
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                vibrateOnlyAlarm(snoozeRepeatCount = 0),
            ),
        )
        val firstInstance = repository.activeInstance(alarm.id)!!.id
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, firstInstance)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, firstInstance)
        assertThat(unreadNotices()).hasSize(1)

        // Tomorrow's ring retires yesterday's notice — and then runs out unattended itself.
        val secondInstance = repository.activeInstance(alarm.id)!!.id
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, secondInstance)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, secondInstance)

        val unread = unreadNotices()
        assertThat(unread.map { it.instanceId }).containsExactly(secondInstance)
    }

    /** A week of not opening the app must not delete the evidence the dot points at. */
    @Test
    fun `pruning keeps a finished chain whose notice is still unread`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 0))
        val instanceId = repository.activeInstance(alarm.id)!!.id
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)

        // Make the row old enough to prune, then prune.
        val row = db.instanceDao().getById(instanceId)!!
        db.instanceDao().upsert(
            row.copy(occurrenceEpochMillis = row.occurrenceEpochMillis - 30L * 24 * 60 * 60 * 1000),
        )
        repository.pruneOldInstances()
        assertThat(unreadNotices()).hasSize(1)

        // Read, it is fair game.
        repository.acknowledgeNoticesOf(alarm.id)
        repository.pruneOldInstances()
        assertThat(db.instanceDao().getById(instanceId)).isNull()
    }

    /**
     * The notification is per-alarm while the rows are per-instance, so reading the notice
     * retires the whole set: acknowledging half would leave a banner standing for a dot
     * that is already gone.
     */
    @Test
    fun `acknowledging retires every unread notice of that alarm`() = runTest {
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                vibrateOnlyAlarm(snoozeRepeatCount = 0),
            ),
        )
        repeat(2) {
            val instanceId = repository.activeInstance(alarm.id)!!.id
            runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
            runtime.handle(
                alarm.id,
                SessionEvent.PlaybackComplete(Instant.now()),
                sink,
                instanceId,
            )
            // Fire acknowledges the previous chain's notice, so only the newest is unread.
        }
        assertThat(unreadNotices()).hasSize(1)

        repository.acknowledgeNoticesOf(alarm.id)

        assertThat(unreadNotices()).isEmpty()
    }

    /** The one button the user has: it must clear the row, not just the banner on screen. */
    @Test
    fun `acknowledging a notice clears the row it came from`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 0))
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)
        assertThat(unreadNotices()).hasSize(1)

        repository.acknowledgeNoticesOf(alarm.id)

        assertThat(unreadNotices()).isEmpty()
        assertThat(db.instanceDao().getById(instanceId)!!.endedReason)
            .isEqualTo(EndedReason.AUTO_DISMISSED)
    }

    @Test
    fun `switching the alarm off yourself leaves no notice behind`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm(snoozeRepeatCount = 3))
        val instanceId = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.UserDismiss(Instant.now()), sink, instanceId)

        assertThat(db.instanceDao().getById(instanceId)!!.endedReason)
            .isEqualTo(EndedReason.USER_DISMISSED)
        assertThat(unattendedNotice()).isNull()
    }

    /** Yesterday's notice must not sit next to tonight's alarm as if it were about it. */
    @Test
    fun `the next ring clears the notice from the morning before`() = runTest {
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                vibrateOnlyAlarm(snoozeRepeatCount = 0),
            ),
        )
        val firstInstance = repository.activeInstance(alarm.id)!!.id

        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, firstInstance)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, firstInstance)
        assertThat(unattendedNotice()).isNotNull()

        // Tomorrow's occurrence rings.
        val nextInstance = repository.activeInstance(alarm.id)!!.id
        assertThat(nextInstance).isNotEqualTo(firstInstance)
        runtime.handle(alarm.id, SessionEvent.Fire(Instant.now()), sink, nextInstance)

        assertThat(unattendedNotice()).isNull()
        assertThat(unattendedNotice()).isNull()
    }

    /**
     * The list screen offers to call off a snooze before it rings. What has to happen then is
     * exactly what dismissing the ring does: the armed snooze is replaced by the alarm's own
     * next occurrence, so tomorrow still works.
     */
    @Test
    fun `cancelling a snooze arms the next occurrence instead of the snooze`() = runTest {
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)),
                vibrateOnlyAlarm(snoozeRepeatCount = 3, snoozeIntervalMinutes = 5),
            ),
        )
        val instanceId = repository.activeInstance(alarm.id)!!.id

        val now = Instant.now()
        runtime.handle(alarm.id, SessionEvent.Fire(now), sink, instanceId)
        runtime.handle(alarm.id, SessionEvent.PlaybackComplete(now), sink, instanceId)
        assertThat(repository.activeInstance(alarm.id)!!.state).isEqualTo(InstanceState.SNOOZED)
        val snoozeTrigger = scheduledAlarms().single().triggerAtTime

        // What the banner's button does.
        runtime.handle(alarm.id, SessionEvent.UserDismiss(Instant.now()), sink, instanceId)

        val finished = db.instanceDao().getById(instanceId)!!
        assertThat(finished.state).isEqualTo(InstanceState.DONE)
        assertThat(finished.endedReason).isEqualTo(EndedReason.USER_DISMISSED)

        val next = repository.activeInstance(alarm.id)!!
        assertThat(next.id).isNotEqualTo(instanceId)
        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(scheduledAlarms().single().triggerAtTime)
            .isEqualTo(next.nextActionEpochMillis)
        // Tomorrow, not five minutes from now.
        assertThat(scheduledAlarms().single().triggerAtTime).isNotEqualTo(snoozeTrigger)
        // And nothing is left claiming to be waiting on a snooze.
        assertThat(repository.observeSnoozedInstances().first()).isEmpty()
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

    /**
     * A date list is the one schedule that runs out. Dates that have gone by are dropped as
     * they pass, so the alarm eventually reports "nothing left" and switches itself off
     * instead of re-evaluating the same past dates forever.
     */
    @Test
    fun `a date list drops dates as they pass and arms the next one`() = runTest {
        val today = LocalDate.now(zone)
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Dates(
                    dates = listOf(today.minusDays(3), today.plusDays(2)),
                    time = LocalTime.of(7, 0),
                ),
                vibrateOnlyAlarm(),
            ),
        )

        val stored = repository.getAlarm(alarm.id)!!
        val remaining = (repository.scheduleOf(stored) as Schedule.Dates).dates
        assertThat(remaining).containsExactly(today.plusDays(2))
        assertThat(stored.enabled).isTrue()
        assertThat(scheduledAlarms()).hasSize(1)
    }

    @Test
    fun `a date list with nothing left switches the alarm off`() = runTest {
        val today = LocalDate.now(zone)
        val alarm = createAlarm(
            ScheduleCodec.encode(
                Schedule.Dates(dates = listOf(today.minusDays(5)), time = LocalTime.of(7, 0)),
                vibrateOnlyAlarm(),
            ),
        )

        assertThat(repository.getAlarm(alarm.id)!!.enabled).isFalse()
        assertThat(scheduledAlarms()).isEmpty()
        assertThat(repository.activeInstance(alarm.id)).isNull()
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

    /**
     * Found on a device: opening the app and the boot receiver both re-armed at the
     * same moment, and `scheduleNextOccurrence` clears the old instance before
     * inserting the new one. Interleaved, both inserted — leaving two live instances
     * for one alarm, with the armed trigger carrying one id while lookups returned the
     * other. The orphan then reported itself missed on every later re-arm.
     */
    @Test
    fun `concurrent re-arming leaves exactly one live instance`() = runBlocking {
        val alarm = createAlarm(vibrateOnlyAlarm())

        val jobs = List(8) { index ->
            launch(Dispatchers.Default) {
                if (index % 2 == 0) {
                    scheduler.armAll(runtime)
                } else {
                    scheduler.onAlarmSaved(repository.getAlarm(alarm.id)!!)
                }
            }
        }
        jobs.joinAll()

        val live = repository.allActiveInstances().filter { it.alarmId == alarm.id }
        assertThat(live).hasSize(1)
        assertThat(scheduledAlarms()).hasSize(1)
        assertThat(scheduledAlarms().single().triggerAtTime)
            .isEqualTo(live.single().nextActionEpochMillis)
    }

    /** Belt and braces: if a stray duplicate ever exists, the newest row must win. */
    @Test
    fun `the active instance lookup is deterministic when duplicates exist`() = runTest {
        val alarm = createAlarm(vibrateOnlyAlarm())
        val occurrence = repository.activeInstance(alarm.id)!!.occurrenceEpochMillis
        val strayId = repository.saveInstance(
            AlarmInstanceEntity(
                alarmId = alarm.id,
                occurrenceEpochMillis = occurrence,
                state = InstanceState.SCHEDULED,
                nextActionEpochMillis = occurrence,
            ),
        )
        assertThat(repository.activeInstance(alarm.id)!!.id).isEqualTo(strayId)
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
