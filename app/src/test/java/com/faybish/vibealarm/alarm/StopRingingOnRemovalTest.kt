package com.faybish.vibealarm.alarm

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.ui.alarms.AlarmListViewModel
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
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
 * Switching an alarm off, or deleting it, while it is ringing has to stop the noise.
 *
 * Cancelling the armed trigger says nothing about the ring already in progress: without
 * this the service plays to the end of its window — up to half an hour for a ringtone —
 * for an alarm the user has just turned off or thrown away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopRingingOnRemovalTest {

    private lateinit var application: Application
    private lateinit var viewModel: AlarmListViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTests(application)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel = AlarmListViewModel()
    }

    @After
    fun tearDown() = AlarmRingingServiceTestAccess.setPlaying(null)

    private fun createRingingAlarm(): Long = runBlocking {
        val id = AppGraph.repository.saveAlarm(
            AlarmEntity(timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(7, 0))),
        )
        AppGraph.repository.getAlarm(id)?.let { AppGraph.scheduler.onAlarmSaved(it) }
        // Pretend the service is mid-ring for this alarm.
        AlarmRingingServiceTestAccess.setPlaying(id)
        id
    }

    /** The view model launches on the main dispatcher; let those coroutines run. */
    private fun settle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun stoppedRingingService(): Boolean {
        val stopped = shadowOf(application).nextStoppedService ?: return false
        return stopped.component?.className == AlarmRingingService::class.java.name
    }

    @Test
    fun `switching a ringing alarm off stops it playing`() = runTest {
        val id = createRingingAlarm()

        viewModel.setEnabled(id, enabled = false)
        settle()

        assertThat(stoppedRingingService()).isTrue()
    }

    @Test
    fun `deleting a ringing alarm stops it playing`() = runTest {
        val id = createRingingAlarm()

        viewModel.delete(id)
        settle()

        assertThat(stoppedRingingService()).isTrue()
    }

    /** Only the alarm that is actually playing may be silenced. */
    @Test
    fun `removing one alarm never silences another alarm's ring`() = runTest {
        val ringing = createRingingAlarm()
        val other = AppGraph.repository.saveAlarm(
            AlarmEntity(timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(8, 0))),
        )
        assertThat(other).isNotEqualTo(ringing)

        viewModel.delete(other)
        settle()

        assertThat(stoppedRingingService()).isFalse()
    }

}
