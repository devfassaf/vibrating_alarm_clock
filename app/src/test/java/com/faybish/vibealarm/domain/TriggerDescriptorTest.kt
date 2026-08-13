package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class TriggerDescriptorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()

    // Friday night, the hours this app was written for.
    private val now = at("2026-08-14T23:40:00")

    @Test
    fun `later the same day is today`() {
        assertThat(TriggerDescriptor.describe(at("2026-08-14T23:59:00"), now, zone))
            .isEqualTo(TriggerWhen.Today)
    }

    /** 20 minutes away, but a different date — and that is what the user needs to hear. */
    @Test
    fun `just after midnight is tomorrow, not today`() {
        assertThat(TriggerDescriptor.describe(at("2026-08-15T00:00:00"), now, zone))
            .isEqualTo(TriggerWhen.Tomorrow)
    }

    /** 22 hours away and still today: the calendar day is what people plan by. */
    @Test
    fun `much later the same day is still today`() {
        val earlyMorning = at("2026-08-14T01:00:00")
        assertThat(TriggerDescriptor.describe(at("2026-08-14T23:00:00"), earlyMorning, zone))
            .isEqualTo(TriggerWhen.Today)
    }

    @Test
    fun `the rest of the coming week is named by weekday`() {
        assertThat(TriggerDescriptor.describe(at("2026-08-16T07:30:00"), now, zone))
            .isEqualTo(TriggerWhen.ThisWeek(DayOfWeek.SUNDAY))
        assertThat(TriggerDescriptor.describe(at("2026-08-20T07:30:00"), now, zone))
            .isEqualTo(TriggerWhen.ThisWeek(DayOfWeek.THURSDAY))
    }

    /** Day 7 carries today's own weekday name, which would read as this week. */
    @Test
    fun `a week out gets a date instead of a weekday`() {
        assertThat(TriggerDescriptor.describe(at("2026-08-21T07:30:00"), now, zone))
            .isEqualTo(TriggerWhen.Later(LocalDate.parse("2026-08-21")))
    }

    @Test
    fun `a trigger already past reads as today`() {
        assertThat(TriggerDescriptor.describe(at("2026-08-13T07:30:00"), now, zone))
            .isEqualTo(TriggerWhen.Today)
    }

    /** The zone decides which day it is; the same instant can be either. */
    @Test
    fun `the day is counted in the phone's own zone`() {
        val trigger = at("2026-08-15T00:30:00")
        assertThat(TriggerDescriptor.describe(trigger, now, zone)).isEqualTo(TriggerWhen.Tomorrow)
        // In UTC that instant is still 21:30 on the 14th.
        assertThat(TriggerDescriptor.describe(trigger, now, ZoneId.of("UTC")))
            .isEqualTo(TriggerWhen.Today)
    }

    @Test
    fun `remaining time is never negative`() {
        assertThat(TriggerDescriptor.remaining(at("2026-08-13T07:30:00"), now))
            .isEqualTo(Duration.ZERO)
        assertThat(TriggerDescriptor.remaining(at("2026-08-15T00:40:00"), now))
            .isEqualTo(Duration.ofHours(1))
    }
}
