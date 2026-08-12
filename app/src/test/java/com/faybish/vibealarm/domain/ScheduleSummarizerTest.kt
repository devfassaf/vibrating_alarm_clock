package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

class ScheduleSummarizerTest {

    @Test
    fun `one time summarizes as once`() {
        val summary = ScheduleSummarizer.summarize(Schedule.OneTime(LocalTime.of(7, 0)))
        assertThat(summary).isEqualTo(ScheduleSummary.Once)
    }

    @Test
    fun `all seven days summarize as every day`() {
        val schedule = Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0))
        assertThat(ScheduleSummarizer.summarize(schedule)).isEqualTo(ScheduleSummary.EveryDay)
    }

    @Test
    fun `monday to friday summarizes as weekdays`() {
        val schedule = Schedule.Weekly(
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            ),
            LocalTime.of(7, 0),
        )
        assertThat(ScheduleSummarizer.summarize(schedule)).isEqualTo(ScheduleSummary.Weekdays)
    }

    @Test
    fun `empty day set summarizes as never`() {
        val schedule = Schedule.Weekly(emptySet(), LocalTime.of(7, 0))
        assertThat(ScheduleSummarizer.summarize(schedule)).isEqualTo(ScheduleSummary.Never)
    }

    @Test
    fun `arbitrary days are listed in the locale's week order`() {
        val schedule = Schedule.Weekly(
            setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY),
            LocalTime.of(7, 0),
        )
        val mondayFirst = ScheduleSummarizer.summarize(schedule, DayOfWeek.MONDAY)
        assertThat(mondayFirst).isEqualTo(
            ScheduleSummary.Days(listOf(DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)),
        )
        // Hebrew locales start the week on Sunday, which flips the order.
        val sundayFirst = ScheduleSummarizer.summarize(schedule, DayOfWeek.SUNDAY)
        assertThat(sundayFirst).isEqualTo(
            ScheduleSummary.Days(listOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY)),
        )
    }

    @Test
    fun `dates summarize with count and earliest date`() {
        val schedule = Schedule.Dates(
            dates = listOf(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 8, 30)),
            time = LocalTime.of(6, 0),
        )
        assertThat(ScheduleSummarizer.summarize(schedule))
            .isEqualTo(ScheduleSummary.DateCount(2, LocalDate.of(2026, 8, 30)))
    }

    @Test
    fun `week order starts at the given day`() {
        assertThat(ScheduleSummarizer.weekOrder(DayOfWeek.SUNDAY).first())
            .isEqualTo(DayOfWeek.SUNDAY)
        assertThat(ScheduleSummarizer.weekOrder(DayOfWeek.SUNDAY)).hasSize(7)
        assertThat(ScheduleSummarizer.weekOrder(DayOfWeek.SUNDAY).last())
            .isEqualTo(DayOfWeek.SATURDAY)
    }

    @Test
    fun `distinct times include every per-day override`() {
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
            defaultTime = LocalTime.of(7, 0),
            overrides = mapOf(DayOfWeek.TUESDAY to LocalTime.of(8, 0)),
        )
        assertThat(ScheduleSummarizer.distinctTimes(schedule))
            .containsExactly(LocalTime.of(7, 0), LocalTime.of(8, 0))
            .inOrder()
    }

    @Test
    fun `distinct times collapse an override equal to the default`() {
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            defaultTime = LocalTime.of(7, 0),
            overrides = mapOf(DayOfWeek.TUESDAY to LocalTime.of(7, 0)),
        )
        assertThat(ScheduleSummarizer.distinctTimes(schedule)).containsExactly(LocalTime.of(7, 0))
    }

    @Test
    fun `overridden days exclude redundant and unselected entries`() {
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            defaultTime = LocalTime.of(7, 0),
            overrides = mapOf(
                DayOfWeek.TUESDAY to LocalTime.of(8, 0),
                // Same as the default: not really an override.
                DayOfWeek.MONDAY to LocalTime.of(7, 0),
                // Not a selected day.
                DayOfWeek.FRIDAY to LocalTime.of(5, 0),
            ),
        )
        assertThat(ScheduleSummarizer.overriddenDays(schedule, DayOfWeek.MONDAY))
            .containsExactly(DayOfWeek.TUESDAY)
    }
}
