package com.faybish.vibealarm.ui.format

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
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
 * The bubble shown after every save, in both languages.
 *
 * It is the only place the app promises anything about tomorrow morning, so the wording is
 * pinned: the day, the time, and how long from now — and, when the alarm cannot ring, that
 * instead of a number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TriggerAnnouncementTest {

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
    private val now = LocalDateTime.parse("2026-08-14T23:40:00").atZone(zone).toInstant()

    /** The clock format is a device setting, and these assertions quote exact times. */
    @Before
    fun use24HourClock() {
        val application: Application = ApplicationProvider.getApplicationContext()
        Settings.System.putString(
            application.contentResolver,
            Settings.System.TIME_12_24,
            "24",
        )
    }

    private fun at(text: String): Instant =
        LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun localized(language: String): Context {
        val application: Application = ApplicationProvider.getApplicationContext()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return application.createConfigurationContext(configuration)
    }

    private fun announce(
        language: String,
        trigger: Instant?,
        enabled: Boolean = true,
    ): String = triggerAnnouncement(
        context = localized(language),
        locale = Locale.forLanguageTag(language),
        enabled = enabled,
        trigger = trigger,
        now = now,
        zone = zone,
    )

    @Test
    fun `Hebrew names the day, the time and what is left`() {
        assertThat(announce("iw", at("2026-08-16T07:30:00")))
            .isEqualTo("ההשכמה תופעל ביום ראשון ב-7:30 · בעוד יום ו-7 שעות")
    }

    @Test
    fun `English names the day, the time and what is left`() {
        assertThat(announce("en", at("2026-08-16T07:30:00")))
            .isEqualTo("Alarm set for Sunday at 7:30 · in 1 day 7 hours")
    }

    @Test
    fun `tonight reads as today`() {
        assertThat(announce("iw", at("2026-08-14T23:55:00")))
            .isEqualTo("ההשכמה תופעל היום ב-23:55 · בעוד 15 דקות")
    }

    /** Twenty minutes away, and still tomorrow — the date is what people plan by. */
    @Test
    fun `past midnight reads as tomorrow`() {
        assertThat(announce("iw", at("2026-08-15T00:00:00")))
            .isEqualTo("ההשכמה תופעל מחר ב-0:00 · בעוד 20 דקות")
    }

    /**
     * Hebrew inflects the unit, so the countdown is assembled from plural parts. "1 ימים"
     * is what a single format string produces, and it is wrong.
     */
    @Test
    fun `Hebrew inflects days, hours and minutes`() {
        assertThat(announce("iw", at("2026-08-17T22:40:00"))).contains("בעוד יומיים ו-23 שעות")
        assertThat(announce("iw", at("2026-08-16T22:40:00"))).contains("בעוד יום ו-23 שעות")
        assertThat(announce("iw", at("2026-08-15T00:41:00"))).contains("בעוד שעה ודקה")
        assertThat(announce("iw", at("2026-08-15T01:42:00"))).contains("בעוד שעתיים ושתי דקות")
        assertThat(announce("iw", at("2026-08-14T23:41:00"))).contains("בעוד דקה")
    }

    /** An alarm set for half a minute from now must not be confirmed as "in 0 minutes". */
    @Test
    fun `under a minute says less than a minute`() {
        assertThat(announce("iw", at("2026-08-14T23:40:30"))).contains("בעוד פחות מדקה")
        assertThat(announce("en", at("2026-08-14T23:40:30"))).contains("in less than a minute")
    }

    @Test
    fun `further out than a week gets a date`() {
        assertThat(announce("en", at("2026-08-25T07:30:00")))
            .contains("Aug 25, 2026")
    }

    /** A saved alarm that is switched off must never be confirmed with a countdown. */
    @Test
    fun `a switched-off alarm says so instead of counting down`() {
        val message = announce("iw", at("2026-08-16T07:30:00"), enabled = false)
        assertThat(message).isEqualTo("ההשכמה כבויה — היא לא תופעל עד שתדליקו אותה.")
        assertThat(message).doesNotContain("בעוד")
    }

    /** Weekly with no days ticked, or a date list that has gone by. */
    @Test
    fun `an alarm with no upcoming time says that too`() {
        assertThat(announce("iw", trigger = null))
            .isEqualTo("אין מועד עתידי להשכמה הזו — בחרו ימים או תאריכים.")
        assertThat(announce("en", trigger = null))
            .isEqualTo("This alarm has no upcoming time — pick days or dates.")
    }

    // --- naming one alarm, for the dialogs a long press opens ---

    /**
     * Both actions behind a long press are hard to take back, and the gesture lands on a
     * list where alarms differ by fifteen minutes — so the dialog has to say which one it
     * is holding, in the same clock format the list uses.
     */
    @Test
    fun `an alarm is named by its time and label`() {
        val context = localized("iw")
        val alarm = com.faybish.vibealarm.data.AlarmEntity(
            label = "השכמת שבת",
            timeMinutesOfDay = 7 * 60 + 30,
        )

        assertThat(alarmDescription(context, Locale("iw"), alarm)).isEqualTo("7:30 · השכמת שבת")
    }

    @Test
    fun `an unlabelled alarm is just its time`() {
        val context = localized("iw")
        val alarm = com.faybish.vibealarm.data.AlarmEntity(timeMinutesOfDay = 6 * 60 + 5)

        assertThat(alarmDescription(context, Locale("iw"), alarm)).isEqualTo("6:05")
    }

    @Test
    fun `English names it too`() {
        val context = localized("en")
        val alarm = com.faybish.vibealarm.data.AlarmEntity(
            label = "Wake up",
            timeMinutesOfDay = 7 * 60 + 30,
        )

        assertThat(alarmDescription(context, Locale.ENGLISH, alarm)).isEqualTo("7:30 · Wake up")
    }
}
