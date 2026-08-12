package com.faybish.vibealarm.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Computes the next wall-clock occurrence of a schedule, strictly after a given instant.
 *
 * DST policy (deliberate, matches AOSP DeskClock): we build each candidate with
 * [ZonedDateTime.of] and accept java.time's default resolver —
 *  - a time inside a spring-forward gap shifts forward by the gap length
 *    (02:30 in a 02:00→03:00 gap fires at 03:30);
 *  - an ambiguous fall-back time resolves to the EARLIER offset (first occurrence).
 */
object NextOccurrenceCalculator {

    /** @return the next trigger strictly after [after], or null if none exists. */
    fun nextTrigger(schedule: Schedule, after: Instant, zone: ZoneId): Instant? = when (schedule) {
        is Schedule.OneTime -> {
            val startDate = after.atZone(zone).toLocalDate()
            // Check today and tomorrow; today's candidate may already have passed.
            // A DST gap can also push today's candidate around, so compare as instants.
            (0L..1L).asSequence()
                .map { at(startDate.plusDays(it), schedule.time, zone) }
                .firstOrNull { it.isAfter(after) }
        }

        is Schedule.Weekly -> {
            if (schedule.days.isEmpty()) {
                null
            } else {
                val startDate = after.atZone(zone).toLocalDate()
                // Scan 8 days: covers "today's time already passed" wrapping to the
                // same weekday next week, with per-day time overrides applied.
                (0L..7L).asSequence()
                    .map { startDate.plusDays(it) }
                    .filter { it.dayOfWeek in schedule.days }
                    .map { date ->
                        val time = schedule.overrides[date.dayOfWeek] ?: schedule.defaultTime
                        at(date, time, zone)
                    }
                    .filter { it.isAfter(after) }
                    .minOrNull()
            }
        }

        is Schedule.Dates -> {
            schedule.dates.asSequence()
                .map { at(it, schedule.time, zone) }
                .filter { it.isAfter(after) }
                .minOrNull()
        }
    }

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        ZonedDateTime.of(date, time, zone).toInstant()
}
