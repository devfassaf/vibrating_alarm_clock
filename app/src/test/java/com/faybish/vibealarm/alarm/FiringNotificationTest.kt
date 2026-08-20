package com.faybish.vibealarm.alarm

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Shabbat setting, as encoded in a notification.
 *
 * "Screen stays dark" is not a UI preference — it is the difference between an alarm that
 * wakes one person and an alarm that lights a bedroom at 6am. It lives in two lines of
 * `buildFiring`: which channel, and whether a full-screen intent is attached. Both are easy
 * to lose in a refactor and impossible to notice until someone's room lights up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FiringNotificationTest {

    private lateinit var context: Context
    private lateinit var notifications: AlarmNotifications

    private val alarm = AlarmEntity(id = 2, label = "שבת", timeMinutesOfDay = 7 * 60 + 30)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        notifications = AlarmNotifications(context).also { it.ensureChannels() }
    }

    @Test
    fun `a screen-stays-dark alarm lights nothing up`() {
        val notification = notifications.buildFiring(alarm.copy(turnScreenOn = false), instanceId = 1)

        assertThat(notification.channelId).isEqualTo(AlarmNotifications.CHANNEL_SILENT)
        // No full-screen intent: this is the whole "nothing lights up" promise.
        assertThat(notification.fullScreenIntent).isNull()
    }

    @Test
    fun `an alarm that should light the screen carries the full-screen intent`() {
        val notification = notifications.buildFiring(alarm.copy(turnScreenOn = true), instanceId = 1)

        assertThat(notification.channelId).isEqualTo(AlarmNotifications.CHANNEL_ALERTING)
        assertThat(notification.fullScreenIntent).isNotNull()
    }

    /** Both modes keep the two actions: the shade is the only way in when the screen is dark. */
    @Test
    fun `both modes offer snooze and dismiss`() {
        listOf(true, false).forEach { turnScreenOn ->
            val notification =
                notifications.buildFiring(alarm.copy(turnScreenOn = turnScreenOn), instanceId = 1)

            assertThat(notification.actions).hasLength(2)
            val labels = notification.actions.map { it.title.toString() }
            assertThat(labels).containsExactly(
                context.getString(com.faybish.vibealarm.R.string.action_snooze),
                context.getString(com.faybish.vibealarm.R.string.action_dismiss),
            ).inOrder()
        }
    }

    /** A ringing alarm may not be swiped away, and it is not a "new message". */
    @Test
    fun `the ringing notification is ongoing and categorised as an alarm`() {
        val notification = notifications.buildFiring(alarm, instanceId = 1)

        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_ALARM)
    }

    /**
     * Posted inside the few seconds the platform allows between startForegroundService and
     * startForeground, before the database has been read — so it must not need the alarm.
     */
    @Test
    fun `the placeholder notification exists for both modes`() {
        listOf(true, false).forEach { turnScreenOn ->
            val starting = notifications.buildStarting(turnScreenOn, alarmId = 2, instanceId = 1)
            assertThat(starting.channelId).isEqualTo(
                if (turnScreenOn) AlarmNotifications.CHANNEL_ALERTING else AlarmNotifications.CHANNEL_SILENT,
            )
            assertThat(starting.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        }
    }

    /** An alarm with no label still has to say something. */
    @Test
    fun `an unlabelled alarm falls back to a name`() {
        val notification = notifications.buildFiring(alarm.copy(label = ""), instanceId = 1)

        assertThat(notification.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo(context.getString(com.faybish.vibealarm.R.string.default_alarm_label))
    }

    /** Every channel this app uses must be silent in itself; the engines make the noise. */
    @Test
    fun `no channel carries a sound or a vibration of its own`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        listOf(
            AlarmNotifications.CHANNEL_ALERTING,
            AlarmNotifications.CHANNEL_SILENT,
            AlarmNotifications.CHANNEL_STATUS,
        ).forEach { id ->
            val channel = manager.getNotificationChannel(id)
            assertThat(channel).isNotNull()
            assertThat(channel.sound).isNull()
            assertThat(channel.shouldVibrate()).isFalse()
        }
    }

    /** The alerting channel has to survive Do Not Disturb; that is what it is for. */
    @Test
    fun `the alerting channel bypasses do not disturb`() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)

        assertThat(manager.getNotificationChannel(AlarmNotifications.CHANNEL_ALERTING).canBypassDnd())
            .isTrue()
    }
}
