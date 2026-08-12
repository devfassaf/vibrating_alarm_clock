package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class NextOccurrenceCalculatorTest {

    private val jerusalem = ZoneId.of("Asia/Jerusalem")
    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()

    // --- OneTime ---

    @Test
    fun `one time - before today's time fires today`() {
        val after = at(LocalDate.of(2026, 8, 12), LocalTime.of(6, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(7, 0)), after, jerusalem,
        )
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 8, 12), LocalTime.of(7, 0), jerusalem))
    }

    @Test
    fun `one time - after today's time fires tomorrow`() {
        val after = at(LocalDate.of(2026, 8, 12), LocalTime.of(8, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(7, 0)), after, jerusalem,
        )
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 8, 13), LocalTime.of(7, 0), jerusalem))
    }

    @Test
    fun `one time - exactly now is strictly after, fires tomorrow`() {
        val after = at(LocalDate.of(2026, 8, 12), LocalTime.of(7, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(7, 0)), after, jerusalem,
        )
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 8, 13), LocalTime.of(7, 0), jerusalem))
    }

    // --- Weekly ---

    @Test
    fun `weekly - single day wraps a full week when today's time passed`() {
        // 2026-08-10 is a Monday.
        val monday = LocalDate.of(2026, 8, 10)
        val after = at(monday, LocalTime.of(8, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.Weekly(setOf(DayOfWeek.MONDAY), LocalTime.of(7, 0)), after, jerusalem,
        )
        assertThat(result).isEqualTo(at(monday.plusWeeks(1), LocalTime.of(7, 0), jerusalem))
    }

    @Test
    fun `weekly - all days fires within 24 hours`() {
        val after = at(LocalDate.of(2026, 8, 12), LocalTime.of(8, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.Weekly(DayOfWeek.entries.toSet(), LocalTime.of(7, 0)), after, jerusalem,
        )
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 8, 13), LocalTime.of(7, 0), jerusalem))
    }

    @Test
    fun `weekly - per-day override later than default`() {
        // Monday 07:00 default, Tuesday overridden to 08:00. After Monday's ring:
        val monday = LocalDate.of(2026, 8, 10)
        val after = at(monday, LocalTime.of(7, 30), jerusalem)
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
            defaultTime = LocalTime.of(7, 0),
            overrides = mapOf(DayOfWeek.TUESDAY to LocalTime.of(8, 0)),
        )
        val result = NextOccurrenceCalculator.nextTrigger(schedule, after, jerusalem)
        assertThat(result).isEqualTo(at(monday.plusDays(1), LocalTime.of(8, 0), jerusalem))
    }

    @Test
    fun `weekly - per-day override earlier than default picks correct minimum`() {
        // Sunday 09:00 default; Monday overridden to 05:00. From Saturday night,
        // Sunday 09:00 comes before Monday 05:00.
        val saturday = LocalDate.of(2026, 8, 8)
        val after = at(saturday, LocalTime.of(23, 0), jerusalem)
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
            defaultTime = LocalTime.of(9, 0),
            overrides = mapOf(DayOfWeek.MONDAY to LocalTime.of(5, 0)),
        )
        val result = NextOccurrenceCalculator.nextTrigger(schedule, after, jerusalem)
        assertThat(result).isEqualTo(at(saturday.plusDays(1), LocalTime.of(9, 0), jerusalem))
    }

    @Test
    fun `weekly - override for unselected day is ignored`() {
        val after = at(LocalDate.of(2026, 8, 9), LocalTime.of(0, 0), jerusalem)
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.MONDAY),
            defaultTime = LocalTime.of(7, 0),
            overrides = mapOf(DayOfWeek.WEDNESDAY to LocalTime.of(6, 0)),
        )
        val result = NextOccurrenceCalculator.nextTrigger(schedule, after, jerusalem)
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 8, 10), LocalTime.of(7, 0), jerusalem))
    }

    @Test
    fun `weekly - no days selected yields null`() {
        val after = Instant.parse("2026-08-12T00:00:00Z")
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.Weekly(emptySet(), LocalTime.of(7, 0)), after, jerusalem,
        )
        assertThat(result).isNull()
    }

    // --- Dates ---

    @Test
    fun `dates - picks earliest future date`() {
        val after = at(LocalDate.of(2026, 8, 12), LocalTime.of(10, 0), jerusalem)
        val schedule = Schedule.Dates(
            dates = listOf(LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 15)),
            time = LocalTime.of(6, 30),
        )
        val result = NextOccurrenceCalculator.nextTrigger(schedule, after, jerusalem)
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 8, 15), LocalTime.of(6, 30), jerusalem))
    }

    @Test
    fun `dates - same-day time already passed moves to next date`() {
        val today = LocalDate.of(2026, 8, 12)
        val after = at(today, LocalTime.of(7, 0), jerusalem)
        val schedule = Schedule.Dates(
            dates = listOf(today, today.plusDays(3)),
            time = LocalTime.of(6, 30),
        )
        val result = NextOccurrenceCalculator.nextTrigger(schedule, after, jerusalem)
        assertThat(result).isEqualTo(at(today.plusDays(3), LocalTime.of(6, 30), jerusalem))
    }

    @Test
    fun `dates - exhausted list yields null`() {
        val after = at(LocalDate.of(2026, 8, 12), LocalTime.of(10, 0), jerusalem)
        val schedule = Schedule.Dates(
            dates = listOf(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10)),
            time = LocalTime.of(6, 30),
        )
        assertThat(NextOccurrenceCalculator.nextTrigger(schedule, after, jerusalem)).isNull()
    }

    // --- DST: Asia/Jerusalem ---
    // 2026: IDT starts Friday 2026-03-27 (02:00 -> 03:00), ends Sunday 2026-10-25
    // (02:00 -> 01:00).

    @Test
    fun `dst gap - 0230 on spring-forward day shifts to 0330 IDT`() {
        val gapDay = LocalDate.of(2026, 3, 27)
        val after = at(gapDay, LocalTime.of(0, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(2, 30)), after, jerusalem,
        )
        // 03:30 IDT (+03:00) == 00:30 UTC
        assertThat(result).isEqualTo(Instant.parse("2026-03-27T00:30:00Z"))
    }

    @Test
    fun `dst overlap - 0130 on fall-back day resolves to earlier offset`() {
        val overlapDay = LocalDate.of(2026, 10, 25)
        val after = at(overlapDay, LocalTime.of(0, 0), jerusalem)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(1, 30)), after, jerusalem,
        )
        // First occurrence: 01:30 IDT (+03:00) == 2026-10-24T22:30 UTC
        assertThat(result).isEqualTo(Instant.parse("2026-10-24T22:30:00Z"))
    }

    @Test
    fun `dst - weekly alarm keeps wall time across the spring transition`() {
        val schedule = Schedule.Weekly(setOf(DayOfWeek.FRIDAY), LocalTime.of(7, 0))
        // Before the transition (IST, +02:00): Friday 2026-03-20 07:00 == 05:00 UTC.
        val beforeWeek = NextOccurrenceCalculator.nextTrigger(
            schedule, at(LocalDate.of(2026, 3, 19), LocalTime.NOON, jerusalem), jerusalem,
        )
        assertThat(beforeWeek).isEqualTo(Instant.parse("2026-03-20T05:00:00Z"))
        // After the transition (IDT, +03:00): Friday 2026-03-27 07:00 == 04:00 UTC.
        val afterWeek = NextOccurrenceCalculator.nextTrigger(
            schedule, at(LocalDate.of(2026, 3, 26), LocalTime.NOON, jerusalem), jerusalem,
        )
        assertThat(afterWeek).isEqualTo(Instant.parse("2026-03-27T04:00:00Z"))
    }

    @Test
    fun `dst - new york spring forward gap shifts forward`() {
        // US 2026: spring forward Sunday 2026-03-08, 02:00 -> 03:00.
        val gapDay = LocalDate.of(2026, 3, 8)
        val after = at(gapDay, LocalTime.of(0, 0), newYork)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(2, 30)), after, newYork,
        )
        // 03:30 EDT (-04:00) == 07:30 UTC
        assertThat(result).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"))
    }

    @Test
    fun `no-dst zone is unaffected on transition dates`() {
        val after = at(LocalDate.of(2026, 3, 27), LocalTime.of(0, 0), tokyo)
        val result = NextOccurrenceCalculator.nextTrigger(
            Schedule.OneTime(LocalTime.of(2, 30)), after, tokyo,
        )
        assertThat(result).isEqualTo(at(LocalDate.of(2026, 3, 27), LocalTime.of(2, 30), tokyo))
    }
}
