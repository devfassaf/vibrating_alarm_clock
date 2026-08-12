package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.Schedule
import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

class CodecsTest {

    private val base = AlarmEntity(timeMinutesOfDay = 0)

    @Test
    fun `one time schedule round-trips`() {
        val schedule = Schedule.OneTime(LocalTime.of(6, 45))
        val decoded = ScheduleCodec.decode(ScheduleCodec.encode(schedule, base))
        assertThat(decoded).isEqualTo(schedule)
    }

    @Test
    fun `weekly schedule with per-day overrides round-trips`() {
        val schedule = Schedule.Weekly(
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.SUNDAY),
            defaultTime = LocalTime.of(7, 0),
            overrides = mapOf(
                DayOfWeek.TUESDAY to LocalTime.of(8, 0),
                DayOfWeek.SUNDAY to LocalTime.of(9, 30),
            ),
        )
        val decoded = ScheduleCodec.decode(ScheduleCodec.encode(schedule, base))
        assertThat(decoded).isEqualTo(schedule)
    }

    @Test
    fun `dates schedule round-trips`() {
        val schedule = Schedule.Dates(
            dates = listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 24)),
            time = LocalTime.of(5, 15),
        )
        val decoded = ScheduleCodec.decode(ScheduleCodec.encode(schedule, base))
        assertThat(decoded).isEqualTo(schedule)
    }

    @Test
    fun `switching schedule types clears stale columns`() {
        val weekly = ScheduleCodec.encode(
            Schedule.Weekly(
                days = setOf(DayOfWeek.FRIDAY),
                defaultTime = LocalTime.of(7, 0),
                overrides = mapOf(DayOfWeek.FRIDAY to LocalTime.of(6, 0)),
            ),
            base,
        )
        val backToOneTime = ScheduleCodec.encode(Schedule.OneTime(LocalTime.of(7, 0)), weekly)
        assertThat(backToOneTime.daysBitmask).isEqualTo(0)
        assertThat(backToOneTime.perDayOverridesJson).isNull()
        assertThat(backToOneTime.datesJson).isNull()
    }

    @Test
    fun `days bitmask maps monday to bit zero and sunday to bit six`() {
        assertThat(ScheduleCodec.daysToBitmask(setOf(DayOfWeek.MONDAY))).isEqualTo(1)
        assertThat(ScheduleCodec.daysToBitmask(setOf(DayOfWeek.SUNDAY))).isEqualTo(64)
        assertThat(ScheduleCodec.daysFromBitmask(0b1000001))
            .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
    }

    @Test
    fun `corrupt schedule json degrades to empty rather than crashing`() {
        val corrupt = base.copy(
            scheduleType = ScheduleType.WEEKLY,
            daysBitmask = 1,
            perDayOverridesJson = "{not json",
        )
        val decoded = ScheduleCodec.decode(corrupt) as Schedule.Weekly
        assertThat(decoded.overrides).isEmpty()
    }

    @Test
    fun `segments round-trip`() {
        val segments = listOf(
            PatternSegment.vibrate(300, 120),
            PatternSegment.pause(700),
            PatternSegment.vibrate(1200, 255),
        )
        assertThat(SegmentsCodec.decode(SegmentsCodec.encode(segments))).isEqualTo(segments)
    }

    @Test
    fun `corrupt segments json decodes to empty list`() {
        assertThat(SegmentsCodec.decode("[{broken")).isEmpty()
    }

    @Test
    fun `all presets have non-empty patterns`() {
        PresetPatterns.all.forEach { preset ->
            val segments = SegmentsCodec.decode(preset.segmentsJson)
            assertThat(segments).isNotEmpty()
            assertThat(segments.any { it.type == com.faybish.vibealarm.domain.SegmentType.VIBRATE })
                .isTrue()
        }
    }
}
