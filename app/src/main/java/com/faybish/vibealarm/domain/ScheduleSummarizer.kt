package com.faybish.vibealarm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Structured description of a schedule for the alarm list, kept free of Android
 * resources so the wording stays testable and localizable.
 */
sealed interface ScheduleSummary {
    data object Once : ScheduleSummary
    data object EveryDay : ScheduleSummary

    /**
     * Every selected day, named. There is deliberately no "weekend" or "weekdays"
     * shorthand: which days those are depends on where you live — an app whose whole
     * point is Saturday morning must not label Saturday by an American convention.
     */
    data class Days(val days: List<DayOfWeek>) : ScheduleSummary
    data class DateCount(val count: Int, val first: LocalDate?) : ScheduleSummary
    data object Never : ScheduleSummary
}

object ScheduleSummarizer {

    /**
     * @param weekStart the locale's first day of week, so day chips and summaries
     *   read in the user's order (Sunday first in Hebrew, Monday in most of Europe).
     */
    fun summarize(schedule: Schedule, weekStart: DayOfWeek = DayOfWeek.MONDAY): ScheduleSummary =
        when (schedule) {
            is Schedule.OneTime -> ScheduleSummary.Once

            is Schedule.Weekly -> when {
                schedule.days.isEmpty() -> ScheduleSummary.Never
                schedule.days.size == 7 -> ScheduleSummary.EveryDay
                else -> ScheduleSummary.Days(orderedDays(schedule.days, weekStart))
            }

            is Schedule.Dates -> ScheduleSummary.DateCount(
                count = schedule.dates.size,
                first = schedule.dates.minOrNull(),
            )
        }

    /** The week's days starting at [weekStart], for day-chip rows. */
    fun weekOrder(weekStart: DayOfWeek): List<DayOfWeek> =
        (0..6).map { weekStart.plus(it.toLong()) }

    fun orderedDays(days: Set<DayOfWeek>, weekStart: DayOfWeek): List<DayOfWeek> =
        weekOrder(weekStart).filter { it in days }

    /**
     * The times an alarm can ring, deduplicated — a weekly alarm with per-day
     * overrides has more than one, and the list shows all of them.
     */
    fun distinctTimes(schedule: Schedule): List<LocalTime> = when (schedule) {
        is Schedule.OneTime -> listOf(schedule.time)
        is Schedule.Dates -> listOf(schedule.time)
        is Schedule.Weekly -> {
            if (schedule.days.isEmpty()) {
                listOf(schedule.defaultTime)
            } else {
                schedule.days
                    .map { schedule.overrides[it] ?: schedule.defaultTime }
                    .distinct()
                    .sorted()
            }
        }
    }

    /** Days whose time differs from the default, in week order. */
    fun overriddenDays(schedule: Schedule.Weekly, weekStart: DayOfWeek): List<DayOfWeek> =
        orderedDays(schedule.days, weekStart)
            .filter { schedule.overrides[it] != null && schedule.overrides[it] != schedule.defaultTime }
}
