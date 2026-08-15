package com.faybish.vibealarm.ui.format

import android.content.Context
import com.faybish.vibealarm.R
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * The two sentences the app uses to describe a morning that went wrong.
 *
 * They are built here, once, because they are said twice: in the notification that lands
 * during the night and in the banner the user meets when they open the app. Two copies of
 * the same sentence drift — and the version that drifts is the one nobody re-reads, so the
 * banner would end up explaining a red dot with different words than the dot itself used.
 */
object NoticeText {

    /** "Missed alarm at 07:30" — the miss and the time, which is all a half-awake reader gets. */
    fun unattendedTitle(context: Context, firstRingAt: Instant): String =
        context.getString(R.string.notification_unattended_title, context.clockTime(firstRingAt))

    /**
     * "Wake up · rang twice, until 07:40, and was never dismissed" — the count is the part
     * that answers the only actionable question: was the pattern simply too gentle?
     */
    fun unattendedDetail(
        context: Context,
        label: String,
        firstRingAt: Instant,
        endedAt: Instant?,
        ringCount: Int,
    ): String = context.resources.getQuantityString(
        R.plurals.notification_unattended_text,
        ringCount,
        context.alarmLabel(label),
        context.clockTime(firstRingAt),
        // A chain that ended before this was recorded still has a first ring to name.
        context.clockTime(endedAt ?: firstRingAt),
        ringCount,
    )

    /** "Missed alarm" — this one never rang at all. */
    fun neverRangTitle(context: Context): String =
        context.getString(R.string.notification_missed_title)

    /**
     * Dated, unlike the others: an alarm that never rang is usually from a day the phone
     * spent switched off, and "07:30" alone would read as this morning.
     */
    fun neverRangDetail(context: Context, label: String, occurrence: Instant): String {
        val zoned = occurrence.atZone(ZoneId.systemDefault())
        val when_ = "${formatDate(zoned.toLocalDate(), context.appLocale())} " +
            context.clockTime(occurrence)
        return context.getString(R.string.notification_missed_text, context.alarmLabel(label), when_)
    }

    private fun Context.alarmLabel(label: String): String =
        label.ifBlank { getString(R.string.default_alarm_label) }

    private fun Context.appLocale(): Locale = resources.configuration.locales[0]

    /**
     * The same clock the rest of the app shows: the device's 12/24-hour setting, in the
     * app's language. A notice reading "7:30 AM" beside a screen reading "7:30" sends the
     * user looking for which of the two is wrong.
     */
    private fun Context.clockTime(instant: Instant): String =
        formatTime(this, instant.atZone(ZoneId.systemDefault()).toLocalTime(), appLocale())
}
