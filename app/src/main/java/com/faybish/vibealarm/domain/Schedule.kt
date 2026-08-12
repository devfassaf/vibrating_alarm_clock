package com.faybish.vibealarm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * When an alarm should ring. Times are wall-clock; conversion to an [java.time.Instant]
 * happens only at arming time so system time/zone changes can always re-derive the
 * correct trigger.
 */
sealed interface Schedule {

    /** Rings once, at the next occurrence of [time]. */
    data class OneTime(val time: LocalTime) : Schedule

    /**
     * Repeats weekly on [days]. Each day rings at [defaultTime] unless it has an
     * entry in [overrides] (e.g. Monday 07:00, Tuesday 08:00 in a single alarm).
     * Override entries for days not in [days] are ignored.
     */
    data class Weekly(
        val days: Set<DayOfWeek>,
        val defaultTime: LocalTime,
        val overrides: Map<DayOfWeek, LocalTime> = emptyMap(),
    ) : Schedule

    /** Rings on each of the given calendar [dates] at [time]. */
    data class Dates(
        val dates: List<LocalDate>,
        val time: LocalTime,
    ) : Schedule
}
