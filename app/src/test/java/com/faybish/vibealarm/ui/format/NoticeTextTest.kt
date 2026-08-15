package com.faybish.vibealarm.ui.format

import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.alarm.AlarmNotifications
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
import org.robolectric.annotation.Config

/**
 * The morning-after sentence is said in two places — the notification during the night and
 * the banner inside the app — and the banner exists precisely because the notification is
 * unreadable on a Samsung, where it appears as a pill for about two seconds. If the two
 * ever say different things, the banner stops being an explanation of the red dot and
 * becomes a second, contradictory claim about the same morning.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeTextTest {

    private lateinit var context: Context

    private val alarm = AlarmEntity(id = 1, label = "השכמת שבת", timeMinutesOfDay = 450)
    private val firstRing = at("2026-08-15T07:30:00")
    private val ended = at("2026-08-15T07:40:00")

    @Before
    fun setUp() {
        context = localized("iw")
        // Pinned, or the assertion depends on the machine's clock format.
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
    }

    private fun localized(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale(language))
        return base.createConfigurationContext(config)
    }

    private fun at(local: String): Instant =
        LocalDateTime.parse(local).atZone(ZoneId.systemDefault()).toInstant()

    private fun notification(build: AlarmNotifications.() -> Unit): Notification {
        val notifications = AlarmNotifications(context).also { it.ensureChannels() }
        notifications.build()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        return org.robolectric.Shadows.shadowOf(manager).allNotifications.last()
    }

    @Test
    fun `the banner and the notification say the same thing about an unattended chain`() {
        val posted = notification {
            showUnattended(alarm, firstRingAt = firstRing, endedAt = ended, ringCount = 2)
        }

        assertThat(posted.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo(NoticeText.unattendedTitle(context, firstRing))
        assertThat(posted.extras.getString(Notification.EXTRA_TEXT)).isEqualTo(
            NoticeText.unattendedDetail(context, alarm.label, firstRing, ended, ringCount = 2),
        )
    }

    @Test
    fun `the banner and the notification say the same thing about an alarm that never rang`() {
        val posted = notification { showMissed(alarm, firstRing) }

        assertThat(posted.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo(NoticeText.neverRangTitle(context))
        assertThat(posted.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo(NoticeText.neverRangDetail(context, alarm.label, firstRing))
    }

    @Test
    fun `the title leads with the miss and the hour`() {
        assertThat(NoticeText.unattendedTitle(context, firstRing)).isEqualTo("החמצת השכמה ב-7:30")
    }

    /** Hebrew needs its own plural for two, and "פעמיים" is not "2 פעמים". */
    @Test
    fun `the detail counts rings in Hebrew`() {
        val once = NoticeText.unattendedDetail(context, alarm.label, firstRing, ended, 1)
        val twice = NoticeText.unattendedDetail(context, alarm.label, firstRing, ended, 2)
        val many = NoticeText.unattendedDetail(context, alarm.label, firstRing, ended, 4)

        assertThat(once).contains("פעם אחת")
        assertThat(twice).contains("פעמיים")
        assertThat(many).contains("4")
        assertThat(listOf(once, twice, many)).containsNoDuplicates()
        assertThat(twice).contains("7:40")
    }

    /** A chain from before the end time was recorded still has a sentence to show. */
    @Test
    fun `a missing end time falls back to the first ring instead of a blank`() {
        val text = NoticeText.unattendedDetail(context, alarm.label, firstRing, null, ringCount = 2)

        assertThat(text).contains("7:30")
        assertThat(text).doesNotContain("null")
    }

    @Test
    fun `an unlabelled alarm is still named`() {
        val text = NoticeText.unattendedDetail(context, "", firstRing, ended, ringCount = 1)

        assertThat(text).contains(
            context.getString(com.faybish.vibealarm.R.string.default_alarm_label),
        )
    }

    /** An alarm that never rang is usually from another day, so it carries a date. */
    @Test
    fun `the never-rang detail is dated`() {
        val text = NoticeText.neverRangDetail(context, alarm.label, firstRing)

        assertThat(text).contains("7:30")
        assertThat(text).contains(
            formatDate(firstRing.atZone(ZoneId.systemDefault()).toLocalDate(), Locale("iw")),
        )
    }

    @Test
    fun `English says it too`() {
        val english = localized("en")

        assertThat(NoticeText.unattendedTitle(english, firstRing)).contains("Missed alarm")
        assertThat(NoticeText.unattendedDetail(english, alarm.label, firstRing, ended, 2))
            .contains("never dismissed")
    }
}
