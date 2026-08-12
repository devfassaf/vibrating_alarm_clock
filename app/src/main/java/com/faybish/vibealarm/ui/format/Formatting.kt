package com.faybish.vibealarm.ui.format

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.ScheduleSummarizer
import com.faybish.vibealarm.domain.ScheduleSummary
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
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
fun formatDate(date: LocalDate): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(currentLocale())
        .format(date)

@Composable
fun dayLabel(day: DayOfWeek, short: Boolean = true): String =
    day.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, currentLocale())

/** "Mon, Wed, Fri" / "Every day" / "Once" — the alarm card's schedule line. */
@Composable
fun scheduleSummaryText(schedule: Schedule): String {
    val locale = currentLocale()
    return when (val summary = ScheduleSummarizer.summarize(schedule, weekStart())) {
        ScheduleSummary.Once -> stringResource(R.string.schedule_once)
        ScheduleSummary.EveryDay -> stringResource(R.string.schedule_every_day)
        ScheduleSummary.Weekdays -> stringResource(R.string.schedule_weekdays)
        ScheduleSummary.Weekend -> stringResource(R.string.schedule_weekend)
        ScheduleSummary.Never -> stringResource(R.string.schedule_no_days)
        is ScheduleSummary.Days -> summary.days.joinToString(", ") {
            it.getDisplayName(TextStyle.SHORT, locale)
        }

        is ScheduleSummary.DateCount -> when {
            summary.count == 0 -> stringResource(R.string.schedule_no_dates)
            summary.first != null -> stringResource(
                R.string.schedule_dates_from,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
                    .format(summary.first),
                summary.count,
            )

            else -> stringResource(R.string.schedule_no_dates)
        }
    }
}

/** "in 7 h 20 min" — how long until the alarm rings. */
@Composable
fun timeUntilText(trigger: Instant): String {
    val minutes = Duration.between(Instant.now(), trigger).toMinutes().coerceAtLeast(0)
    val days = minutes / (24 * 60)
    val hours = (minutes % (24 * 60)) / 60
    val mins = minutes % 60
    return when {
        days > 0 -> stringResource(R.string.time_until_days, days, hours)
        hours > 0 -> stringResource(R.string.time_until_hours, hours, mins)
        else -> stringResource(R.string.time_until_minutes, mins)
    }
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
