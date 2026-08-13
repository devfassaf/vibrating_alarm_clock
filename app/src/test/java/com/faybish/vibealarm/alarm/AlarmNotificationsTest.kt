package com.faybish.vibealarm.alarm

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmEntity
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notice left behind after an alarm rang itself out.
 *
 * It is the whole answer to "did the alarm actually work this morning?", and it is read
 * half-awake, so the wording is pinned in both languages — including that it must never
 * read like the other kind of missed alarm, the one where nothing rang at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmNotificationsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
    private lateinit var application: Application

    private val alarm = AlarmEntity(id = 3, label = "שבת", timeMinutesOfDay = 7 * 60 + 30)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        // These assertions quote exact times, and the clock format is a device setting.
        Settings.System.putString(
            application.contentResolver,
            Settings.System.TIME_12_24,
            "24",
        )
    }

    private fun at(text: String): Instant =
        LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun localized(language: String): Context {
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }

    private fun manager(): NotificationManager =
        application.getSystemService(NotificationManager::class.java)

    private fun postUnattended(
        language: String,
        ringCount: Int,
        endedAt: String = "2026-08-15T07:40:00",
    ): Notification {
        val notifications = AlarmNotifications(localized(language))
        notifications.ensureChannels()
        notifications.showUnattended(
            alarm = alarm,
            firstRingAt = at("2026-08-15T07:30:00"),
            endedAt = at(endedAt),
            ringCount = ringCount,
        )
        return shadowOf(manager()).allNotifications.last()
    }

    private fun Notification.title(): String? = extras.getString(Notification.EXTRA_TITLE)

    private fun Notification.text(): String? = extras.getString(Notification.EXTRA_TEXT)

    @Test
    fun `Hebrew says it rang, how many times, and between which hours`() {
        val notification = postUnattended("iw", ringCount = 3)

        assertThat(notification.title()).isEqualTo("ההשכמה פעלה ולא כובתה")
        assertThat(notification.text()).isEqualTo("שבת · פעלה 3 פעמים, בין 7:30 ל-7:40")
    }

    @Test
    fun `English says the same`() {
        val notification = postUnattended("en", ringCount = 3)

        assertThat(notification.title()).isEqualTo("Alarm rang and was never dismissed")
        assertThat(notification.text()).isEqualTo("שבת · rang 3 times, 7:30 to 7:40")
    }

    /** One ring has no range to give — "between 7:30 and 7:30" would read as a fault. */
    @Test
    fun `a single ring names one time`() {
        assertThat(postUnattended("iw", ringCount = 1, endedAt = "2026-08-15T07:30:12").text())
            .isEqualTo("שבת · פעלה פעם אחת ב-7:30")
        assertThat(postUnattended("en", ringCount = 1, endedAt = "2026-08-15T07:30:12").text())
            .isEqualTo("שבת · rang once at 7:30")
    }

    @Test
    fun `Hebrew inflects two rings`() {
        assertThat(postUnattended("iw", ringCount = 2).text())
            .isEqualTo("שבת · פעלה פעמיים, בין 7:30 ל-7:40")
    }

    @Test
    fun `an alarm with no label falls back to a name instead of showing nothing`() {
        val notifications = AlarmNotifications(localized("iw"))
        notifications.ensureChannels()
        notifications.showUnattended(
            alarm = alarm.copy(label = ""),
            firstRingAt = at("2026-08-15T07:30:00"),
            endedAt = at("2026-08-15T07:40:00"),
            ringCount = 2,
        )
        assertThat(shadowOf(manager()).allNotifications.last().text()).startsWith("השכמה ·")
    }

    /**
     * The two notices mean opposite things — "it rang and you slept through it" against
     * "it never rang". Sharing wording, or a notification slot, would make either one
     * useless.
     */
    @Test
    fun `the unattended notice never reads like a missed alarm`() {
        val notifications = AlarmNotifications(localized("iw"))
        notifications.ensureChannels()
        notifications.showMissed(alarm, at("2026-08-15T07:30:00"))
        notifications.showUnattended(
            alarm = alarm,
            firstRingAt = at("2026-08-15T07:30:00"),
            endedAt = at("2026-08-15T07:40:00"),
            ringCount = 3,
        )

        val posted = shadowOf(manager()).allNotifications
        assertThat(posted).hasSize(2)
        assertThat(posted.map { it.title() }).containsNoDuplicates()
        assertThat(posted.map { it.title() }).contains("השכמה הוחמצה")
        assertThat(posted.map { it.title() }).contains("ההשכמה פעלה ולא כובתה")
    }

    /** It lands at the end of the chain, while the rest of the room is still asleep. */
    @Test
    fun `the notice makes no sound and no vibration of its own`() {
        val notification = postUnattended("iw", ringCount = 3)
        assertThat(notification.channelId).isEqualTo(AlarmNotifications.CHANNEL_STATUS)

        val channel = manager().getNotificationChannel(AlarmNotifications.CHANNEL_STATUS)
        assertThat(channel.sound).isNull()
        assertThat(channel.shouldVibrate()).isFalse()
        // Anything higher would pop a heads-up over a dark bedroom.
        assertThat(channel.importance).isAtMost(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun `the alarm ringing again clears the notice from last time`() {
        val notifications = AlarmNotifications(localized("iw"))
        notifications.ensureChannels()
        notifications.showUnattended(
            alarm = alarm,
            firstRingAt = at("2026-08-15T07:30:00"),
            endedAt = at("2026-08-15T07:40:00"),
            ringCount = 3,
        )
        assertThat(shadowOf(manager()).allNotifications).hasSize(1)

        notifications.cancelUnattended(alarm.id)
        assertThat(shadowOf(manager()).allNotifications).isEmpty()
    }

    /** Reached when the user dismisses the alarm: they are awake and holding the phone. */
    @Test
    fun `cancelling an alarm's notifications takes the notice with them`() {
        val notifications = AlarmNotifications(localized("iw"))
        notifications.ensureChannels()
        notifications.showUnattended(
            alarm = alarm,
            firstRingAt = at("2026-08-15T07:30:00"),
            endedAt = at("2026-08-15T07:40:00"),
            ringCount = 3,
        )

        notifications.cancelForAlarm(alarm.id)
        assertThat(shadowOf(manager()).allNotifications).isEmpty()
    }

    /** Two alarms, two slots: silencing one must not erase the other's record. */
    @Test
    fun `each alarm keeps its own notice`() {
        val notifications = AlarmNotifications(localized("iw"))
        notifications.ensureChannels()
        listOf(alarm, alarm.copy(id = 9, label = "חול")).forEach {
            notifications.showUnattended(
                alarm = it,
                firstRingAt = at("2026-08-15T07:30:00"),
                endedAt = at("2026-08-15T07:40:00"),
                ringCount = 2,
            )
        }
        assertThat(shadowOf(manager()).allNotifications).hasSize(2)

        notifications.cancelForAlarm(alarm.id)
        val left = shadowOf(manager()).allNotifications.single()
        assertThat(left.text()).startsWith("חול ·")
    }
}
