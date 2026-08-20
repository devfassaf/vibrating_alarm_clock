package com.faybish.vibealarm.ui.format

import android.content.Context
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ScheduleCodec
import android.text.format.DateFormat
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.ScheduleSummarizer
import com.faybish.vibealarm.domain.ScheduleSummary
import com.faybish.vibealarm.domain.TriggerDescriptor
import com.faybish.vibealarm.domain.TriggerWhen
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** The user's locale, as Compose sees it. */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** First day of the week for the current locale — Sunday in Israel. */
@Composable
fun weekStart(): DayOfWeek = WeekFields.of(currentLocale()).firstDayOfWeek

@Composable
fun formatTime(time: LocalTime): String {
    val context = LocalContext.current
    return timeFormatter(context, currentLocale()).format(time)
}

fun formatTime(context: Context, time: LocalTime, locale: Locale): String =
    timeFormatter(context, locale).format(time)

private fun timeFormatter(context: Context, locale: Locale): DateTimeFormatter {
    val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, locale)
}

@Composable
fun formatDate(date: LocalDate): String = formatDate(date, currentLocale())

fun formatDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

/**
 * Each day is named in full, from our own strings rather than from the platform's
 * abbreviations.
 *
 * The platform's short Hebrew name for Sunday is "יום א׳", which cannot be shortened
 * for a chip without turning all seven days into the same two letters — which is
 * exactly what it did. A day picker whose labels the user has to decode is not a day
 * picker, so every day carries its own name: ראשון ... שבת.
 */
@StringRes
fun dayNameRes(day: DayOfWeek): Int = when (day) {
    DayOfWeek.SUNDAY -> R.string.day_sunday
    DayOfWeek.MONDAY -> R.string.day_monday
    DayOfWeek.TUESDAY -> R.string.day_tuesday
    DayOfWeek.WEDNESDAY -> R.string.day_wednesday
    DayOfWeek.THURSDAY -> R.string.day_thursday
    DayOfWeek.FRIDAY -> R.string.day_friday
    DayOfWeek.SATURDAY -> R.string.day_saturday
}

fun dayName(context: Context, day: DayOfWeek): String = context.getString(dayNameRes(day))

@Composable
fun dayName(day: DayOfWeek): String = stringResource(dayNameRes(day))

/** "ראשון, שבת" / "Every day" / "Once" — the alarm card's schedule line. */
@Composable
fun scheduleSummaryText(schedule: Schedule): String {
    val context = LocalContext.current
    val locale = currentLocale()
    return when (val summary = ScheduleSummarizer.summarize(schedule, weekStart())) {
        ScheduleSummary.Once -> stringResource(R.string.schedule_once)
        ScheduleSummary.EveryDay -> stringResource(R.string.schedule_every_day)
        ScheduleSummary.Never -> stringResource(R.string.schedule_no_days)
        is ScheduleSummary.Days -> summary.days.joinToString(", ") { dayName(context, it) }

        is ScheduleSummary.DateCount -> when {
            summary.count == 0 -> stringResource(R.string.schedule_no_dates)
            summary.first != null -> pluralStringResource(
                R.plurals.schedule_dates_from,
                summary.count,
                formatDate(summary.first, locale),
                summary.count,
            )

            else -> stringResource(R.string.schedule_no_dates)
        }
    }
}

/** "in 7 h 20 min" — how long until the alarm rings. */
@Composable
fun timeUntilText(trigger: Instant): String = timeUntil(LocalContext.current, trigger)

/**
 * Built from pluralized parts rather than one format string, because Hebrew inflects the
 * unit itself: a day is "יום", two are "יומיים", and "1 ימים" is simply wrong.
 */
fun timeUntil(context: Context, trigger: Instant, now: Instant = Instant.now()): String {
    val minutes = TriggerDescriptor.remaining(trigger, now).toMinutes()
    val days = (minutes / (24 * 60)).toInt()
    val hours = ((minutes % (24 * 60)) / 60).toInt()
    val mins = (minutes % 60).toInt()
    return when {
        days > 0 -> context.join(
            context.part(R.plurals.duration_days_part, days),
            context.part(R.plurals.duration_hours_part, hours),
        )

        hours > 0 -> context.join(
            context.part(R.plurals.duration_hours_part, hours),
            context.part(R.plurals.duration_minutes_part, mins),
        )

        mins > 0 -> context.getString(
            R.string.time_until_one,
            context.part(R.plurals.duration_minutes_part, mins),
        )

        // Saving an alarm for 30 seconds' time should not read "in 0 minutes".
        else -> context.getString(R.string.time_until_now)
    }
}

private fun Context.part(@PluralsRes plurals: Int, count: Int): String =
    resources.getQuantityString(plurals, count, count)

/**
 * Hebrew attaches the conjunction to the following word ("ושעה") but keeps a maqaf before
 * a numeral ("ו-23 שעות"), so which joiner to use depends on the text it joins. Both
 * variants are identical in English.
 */
private fun Context.join(first: String, second: String): String = getString(
    if (second.firstOrNull()?.isDigit() == true) {
        R.string.time_until_two_parts
    } else {
        R.string.time_until_two_parts_word
    },
    first,
    second,
)

/**
 * What the confirmation bubble says after a save: which day, what time, and how long
 * from now.
 *
 * All three, because each one alone can be misread — "in 6 hours" hides which morning,
 * and "Saturday 07:30" hides that the alarm is switched off. An alarm the user believes
 * is set and is not is the one failure this app cannot afford.
 */
/**
 * Names one alarm in a sentence, for the dialogs that act on it.
 *
 * A long press lands on a list where alarms differ by fifteen minutes, and both actions it
 * offers are hard to take back — so the dialog has to say which alarm it is holding. The
 * time comes first because that is how the list is read; the label follows when there is
 * one, and an unlabelled alarm is simply its time.
 */
fun alarmDescription(context: Context, locale: Locale, alarm: AlarmEntity): String {
    val time = formatTime(context, ScheduleCodec.minutesToTime(alarm.timeMinutesOfDay), locale)
    return if (alarm.label.isBlank()) time else "$time · ${alarm.label}"
}

fun triggerAnnouncement(
    context: Context,
    locale: Locale,
    enabled: Boolean,
    trigger: Instant?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (!enabled) return context.getString(R.string.announce_disabled)
    if (trigger == null) return context.getString(R.string.announce_no_trigger)

    val time = formatTime(context, trigger.atZone(zone).toLocalTime(), locale)
    val lead = when (val whenItRings = TriggerDescriptor.describe(trigger, now, zone)) {
        TriggerWhen.Today -> context.getString(R.string.announce_today, time)
        TriggerWhen.Tomorrow -> context.getString(R.string.announce_tomorrow, time)
        is TriggerWhen.ThisWeek -> context.getString(
            R.string.announce_this_week,
            dayName(context, whenItRings.day),
            time,
        )

        is TriggerWhen.Later -> context.getString(
            R.string.announce_later,
            formatDate(whenItRings.date, locale),
            time,
        )
    }
    return context.getString(
        R.string.announce_with_remaining,
        lead,
        timeUntil(context, trigger, now),
    )
}

@Composable
fun formatInstantTime(instant: Instant): String = formatTime(
    instant.atZone(ZoneId.systemDefault()).toLocalTime(),
)

/** Pattern length as "3.4 s" / "1:05". */
@Composable
fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = durationMs / 1000.0
    return if (totalSeconds < 60) {
        stringResource(R.string.duration_seconds, String.format(currentLocale(), "%.1f", totalSeconds))
    } else {
        val minutes = durationMs / 60_000
        val seconds = (durationMs % 60_000) / 1000
        stringResource(R.string.duration_minutes, minutes, seconds)
    }
}
