package com.faybish.vibealarm.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * How to name the moment an alarm will ring next.
 *
 * "In 6 hours" alone is not reassuring enough to act on — 6 hours from 01:00 is a
 * different plan than 6 hours from 19:00 — so the confirmation names the day too, and
 * this is the part that has to be right regardless of wording or language.
 */
sealed interface TriggerWhen {
    data object Today : TriggerWhen
    data object Tomorrow : TriggerWhen

    /** Named by weekday, which is only unambiguous inside the coming week. */
    data class ThisWeek(val day: DayOfWeek) : TriggerWhen

    /** Far enough out that the weekday would be ambiguous, so it gets a date. */
    data class Later(val date: LocalDate) : TriggerWhen
}

object TriggerDescriptor {

    /**
     * Counted in calendar days, not in hours: 23:50 → 00:10 is "tomorrow" even though
     * it is 20 minutes away, and 01:00 → 23:00 is "today" even though it is 22 hours off.
     */
    fun describe(trigger: Instant, now: Instant, zone: ZoneId): TriggerWhen {
        val today = now.atZone(zone).toLocalDate()
        val date = trigger.atZone(zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(today, date)) {
            // A trigger in the past is a schedule the user just changed; the moment it
            // matters is now, so it reads as today rather than as a date.
            in Long.MIN_VALUE..0L -> TriggerWhen.Today
            1L -> TriggerWhen.Tomorrow
            // Day 7 carries the same weekday name as today, which would read as the
            // wrong week.
            in 2L..6L -> TriggerWhen.ThisWeek(date.dayOfWeek)
            else -> TriggerWhen.Later(date)
        }
    }

    /** Never negative: an alarm that is already due is "in 0 minutes", not "-3". */
    fun remaining(trigger: Instant, now: Instant): Duration =
        Duration.between(now, trigger).coerceAtLeast(Duration.ZERO)
}
