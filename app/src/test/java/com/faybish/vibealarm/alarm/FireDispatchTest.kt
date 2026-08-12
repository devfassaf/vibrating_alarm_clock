package com.faybish.vibealarm.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Guards the handoff from the trigger broadcast to the ringing service.
 *
 * This exists because of a real bug: the receiver used to run the `Fire` transition
 * itself, which marked the alarm FIRING before the service — the only component that
 * owns the vibrator — got its turn. The reducer then correctly treated the service's
 * `Fire` as a duplicate and returned no effects, so nothing ever vibrated and the
 * chain stalled forever. Every single alarm was silent.
 *
 * The invariant these tests protect: after the broadcast the alarm must still be
 * SCHEDULED and a service start must be pending, so the service's own transition is
 * the one that produces output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FireDispatchTest {

    private lateinit var application: Application

    private val alarmManager: AlarmManager
        get() = AppGraph.deviceProtectedContext.getSystemService(AlarmManager::class.java)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // AppGraph is a process-wide singleton, so without this a previous test class's
        // database would leak in and make these tests order-dependent.
        AppGraph.resetForTests(application)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(application).clearStartedServices()
    }

    private fun createAlarm(): AlarmEntity = runBlocking {
        val id = AppGraph.repository.saveAlarm(
            AlarmEntity(
                label = "fire dispatch",
                enabled = true,
                timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(7, 0)),
                mode = RingMode.VIBRATE_ONLY,
                turnScreenOn = false,
                snoozeRepeatCount = 1,
            ),
        )
        val saved = AppGraph.repository.getAlarm(id)!!
        AppGraph.scheduler.onAlarmSaved(saved)
        saved
    }

    private fun activeInstanceState(alarmId: Long): Int? = runBlocking {
        AppGraph.repository.activeInstance(alarmId)?.state
    }

    private fun fireIntent(alarmId: Long, instanceId: Long) =
        Intent(application, AlarmReceiver::class.java).apply {
            action = AlarmIntents.ACTION_FIRE
            putExtra(AlarmIntents.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmIntents.EXTRA_INSTANCE_ID, instanceId)
        }

    /**
     * The receiver hands off on [AppGraph.appScope], a real background dispatcher, so
     * the assertion has to wait for it rather than assume it already ran.
     */
    private fun <T : Any> awaitNotNull(timeoutMs: Long = 5_000, block: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            block()?.let { return it }
            shadowOf(application.mainLooper).idle()
            Thread.sleep(20)
        }
        return null
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            shadowOf(application.mainLooper).idle()
            Thread.sleep(20)
        }
        return condition()
    }

    private fun awaitQuiet(millis: Long = 400) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(application.mainLooper).idle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `the trigger broadcast starts the ringing service and does not consume the Fire`() {
        val alarm = createAlarm()
        val instanceId = runBlocking { AppGraph.repository.activeInstance(alarm.id)!!.id }

        AlarmReceiver().onReceive(application, fireIntent(alarm.id, instanceId))

        val started = awaitNotNull { shadowOf(application).nextStartedService }
        assertThat(started).isNotNull()
        assertThat(started!!.component?.className)
            .isEqualTo(AlarmRingingService::class.java.name)
        assertThat(started.getLongExtra(AlarmIntents.EXTRA_ALARM_ID, 0)).isEqualTo(alarm.id)
        assertThat(started.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)).isEqualTo(instanceId)
        // Screen-off alarms must be handed over as such, or the service would post the
        // alerting channel and light up the room.
        assertThat(started.getBooleanExtra(AlarmRingingService.EXTRA_TURN_SCREEN_ON, true))
            .isFalse()

        // The critical assertion: the receiver must NOT have advanced the state machine.
        // If it had, the service's Fire would be a no-op and nothing would vibrate.
        assertThat(activeInstanceState(alarm.id)).isEqualTo(InstanceState.SCHEDULED)
    }

    @Test
    fun `a trigger for a deleted alarm starts nothing and cancels the leftover trigger`() {
        val alarm = createAlarm()
        val instanceId = runBlocking { AppGraph.repository.activeInstance(alarm.id)!!.id }
        runBlocking { AppGraph.repository.deleteAlarm(alarm.id) }
        shadowOf(application).clearStartedServices()

        AlarmReceiver().onReceive(application, fireIntent(alarm.id, instanceId))
        // The receiver should cancel the orphaned trigger rather than start anything.
        awaitTrue { shadowOf(alarmManager).scheduledAlarms.isEmpty() }

        assertThat(shadowOf(application).nextStartedService).isNull()
        assertThat(shadowOf(alarmManager).scheduledAlarms).isEmpty()
    }

    @Test
    fun `an unknown action is ignored`() {
        val alarm = createAlarm()
        val instanceId = runBlocking { AppGraph.repository.activeInstance(alarm.id)!!.id }
        shadowOf(application).clearStartedServices()

        val intent = fireIntent(alarm.id, instanceId).setAction("com.faybish.vibealarm.action.NOPE")
        AlarmReceiver().onReceive(application, intent)
        awaitQuiet()

        assertThat(shadowOf(application).nextStartedService).isNull()
        assertThat(activeInstanceState(alarm.id)).isEqualTo(InstanceState.SCHEDULED)
    }

    /**
     * The settings file must live in device-protected storage. `preferencesDataStoreFile`
     * silently resolves through the *application* context, which is always
     * credential-encrypted — that file would be unreadable after a reboot until the
     * phone is unlocked, and it is read while an alarm rings.
     */
    @Test
    fun `settings are stored in device-protected storage, not with the app's other files`() {
        val settingsFile = AppGraph.settings.file
        val deviceProtected = AppGraph.deviceProtectedContext.filesDir
        val credentialEncrypted = application.filesDir

        assertThat(settingsFile.canonicalPath).startsWith(deviceProtected.canonicalPath)
        assertThat(settingsFile.canonicalPath).doesNotContain(credentialEncrypted.canonicalPath)
        // Sanity check that the two really are different locations, or the test is vacuous.
        assertThat(deviceProtected.canonicalPath).isNotEqualTo(credentialEncrypted.canonicalPath)
    }

    /** Same requirement for the database that holds the alarms themselves. */
    @Test
    fun `the alarm database is stored in device-protected storage`() {
        val dbPath = AppGraph.deviceProtectedContext.getDatabasePath("alarms.db").canonicalPath
        val credentialDbPath = application.getDatabasePath("alarms.db").canonicalPath
        assertThat(dbPath).isNotEqualTo(credentialDbPath)
    }
}
