package com.faybish.vibealarm.alarm

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * How the ringing screen reaches the user, which is where the app was quietly broken.
 *
 * The screen was only ever launched by the notification's full-screen intent, and that intent
 * was attached to the *update* that [AlarmNotifications.buildFiring] posts a moment after the
 * service's `startForeground`. SystemUI weighs a full-screen intent only when a notification
 * is added, so it never saw ours: measured on a dozing phone, the first ring of a chain got
 * the screen and the second silently did not — an alarm vibrating with no snooze and no
 * dismiss, and no activity to read the volume keys either. The only way out was to unlock
 * the phone and switch the alarm off from inside the app.
 *
 * So the intent now rides on the post that adds the notification too. These are the two
 * facts that has to keep being true, plus the one that must never become true: an alarm whose
 * screen stays dark may not light anything up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RingingScreenLaunchTest {

    private lateinit var context: Context
    private lateinit var notifications: AlarmNotifications

    private val alarm = AlarmEntity(id = 3, label = "שבת", timeMinutesOfDay = 7 * 60 + 30)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        notifications = AlarmNotifications(context).also { it.ensureChannels() }
    }

    /** The post that adds the notification is the only one the platform will act on. */
    @Test
    fun `the notification that starts the service carries the full-screen intent`() {
        val starting = notifications.buildStarting(
            turnScreenOn = true,
            alarmId = alarm.id,
            instanceId = 9,
        )

        assertThat(starting.fullScreenIntent).isNotNull()
        assertThat(starting.channelId).isEqualTo(AlarmNotifications.CHANNEL_ALERTING)
    }

    /** The Shabbat mode, from the side that used to be the only launcher of the screen. */
    @Test
    fun `a screen-stays-dark alarm attaches nothing, even on the starting notification`() {
        val starting = notifications.buildStarting(
            turnScreenOn = false,
            alarmId = alarm.id,
            instanceId = 9,
        )

        assertThat(starting.fullScreenIntent).isNull()
        assertThat(starting.channelId).isEqualTo(AlarmNotifications.CHANNEL_SILENT)
    }

    /**
     * The service posts this before it has read the database — in the rare path where it has
     * no ids yet, it must still be a legal notification rather than a crash on the alarm path.
     */
    @Test
    fun `without ids the starting notification is still valid`() {
        val starting = notifications.buildStarting(turnScreenOn = true)

        assertThat(starting.fullScreenIntent).isNull()
        assertThat(starting.channelId).isEqualTo(AlarmNotifications.CHANNEL_ALERTING)
    }

    /**
     * One ring can now start the screen twice — the platform honouring the intent, and the
     * service starting it itself because it cannot know whether the platform did. CLEAR_TASK
     * made the second start tear down what the first had just shown; singleInstance turns a
     * duplicate into an onNewIntent instead.
     */
    @Test
    fun `starting the screen twice for one ring is harmless`() {
        val fromService = AlarmIntents.ringingActivity(context, alarmId = 3, instanceId = 9)

        assertThat(fromService.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
        assertThat(fromService.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(fromService.getLongExtra(AlarmIntents.EXTRA_ALARM_ID, 0)).isEqualTo(3)
        assertThat(fromService.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)).isEqualTo(9)
    }

    @Test
    fun `the platform's own launch does not clear the task either`() {
        val pending = AlarmIntents.ringingActivityIntent(context, alarmId = 3, instanceId = 9)

        val intent = shadowOf(pending).savedIntent
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
        assertThat(intent.component?.className).contains("AlarmActivity")
    }

    /** Both launches must land on the same activity, or one of them opens a second screen. */
    @Test
    fun `both routes point at the same screen with the same ids`() {
        val pending = shadowOf(AlarmIntents.ringingActivityIntent(context, 3, 9)).savedIntent
        val direct = AlarmIntents.ringingActivity(context, 3, 9)

        assertThat(direct.component).isEqualTo(pending.component)
        assertThat(direct.data).isEqualTo(pending.data)
        assertThat(direct.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0))
            .isEqualTo(pending.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0))
    }
}
