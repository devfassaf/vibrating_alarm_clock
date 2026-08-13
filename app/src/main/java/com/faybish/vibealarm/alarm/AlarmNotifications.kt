package com.faybish.vibealarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.ui.format.formatDate
import com.faybish.vibealarm.ui.format.formatTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Notification channels and builders for the alarm lifecycle.
 *
 * Two alerting channels exist on purpose:
 *  - [CHANNEL_ALERTING] (high importance, full-screen intent) for a normal alarm
 *    that should light up the screen with Dismiss/Snooze buttons;
 *  - [CHANNEL_SILENT] (low importance, no full-screen intent, no heads-up) for the
 *    "screen stays dark" alarm: the phone vibrates and nothing lights up. It is
 *    still a foreground-service notification, just an unobtrusive one.
 *
 * Neither channel carries a sound or a vibration pattern — output is driven
 * explicitly by the engines so the user's own pattern is what plays.
 */
class AlarmNotifications(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val alerting = NotificationChannel(
            CHANNEL_ALERTING,
            context.getString(R.string.channel_alerting_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerting_description)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val silent = NotificationChannel(
            CHANNEL_SILENT,
            context.getString(R.string.channel_silent_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_silent_description)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        val status = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_status_description)
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(alerting, silent, status))
    }

    /**
     * The ringing service's foreground notification. When the alarm is configured
     * to keep the screen off it goes to the silent channel and omits the
     * full-screen intent, so nothing wakes the display.
     */
    fun buildFiring(alarm: AlarmEntity, instanceId: Long): Notification {
        val channel = if (alarm.turnScreenOn) CHANNEL_ALERTING else CHANNEL_SILENT
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(alarm.displayLabel())
            .setContentText(context.getString(R.string.notification_firing_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_snooze),
                    AlarmIntents.snoozePendingIntent(context, alarm.id, instanceId),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_dismiss),
                    AlarmIntents.dismissPendingIntent(context, alarm.id, instanceId),
                ).build(),
            )

        if (alarm.turnScreenOn) {
            val fullScreen = AlarmIntents.ringingActivityIntent(context, alarm.id, instanceId)
            builder.setFullScreenIntent(fullScreen, true)
            builder.setContentIntent(fullScreen)
        } else {
            builder.setContentIntent(AlarmIntents.appPendingIntent(context, alarm.id))
        }
        return builder.build()
    }

    /** Minimal notification posted within the service's startForeground deadline. */
    fun buildStarting(turnScreenOn: Boolean): Notification =
        Notification.Builder(context, if (turnScreenOn) CHANNEL_ALERTING else CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(context.getString(R.string.notification_firing_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    fun postFiring(alarm: AlarmEntity, instanceId: Long) {
        manager.notify(firingId(alarm.id), buildFiring(alarm, instanceId))
    }

    fun showSnoozed(alarm: AlarmEntity, instanceId: Long, until: Instant, remaining: Int?) {
        val time = until.asClockTime()
        val text = if (remaining == null) {
            context.getString(R.string.notification_snoozed_until, time)
        } else {
            context.resources.getQuantityString(
                R.plurals.notification_snoozed_until_remaining,
                remaining,
                time,
                remaining,
            )
        }
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(alarm.displayLabel())
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .setContentIntent(AlarmIntents.appPendingIntent(context, alarm.id))
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_dismiss),
                    AlarmIntents.dismissPendingIntent(context, alarm.id, instanceId),
                ).build(),
            )
            .build()
        manager.notify(snoozedId(alarm.id), notification)
    }

    fun showMissed(alarm: AlarmEntity, occurrence: Instant) {
        val zoned = occurrence.atZone(ZoneId.systemDefault())
        // Dated, because a missed alarm can be from a day the phone spent switched off.
        val time = "${formatDate(zoned.toLocalDate(), appLocale())} ${occurrence.asClockTime()}"
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(context.getString(R.string.notification_missed_title))
            .setContentText(context.getString(R.string.notification_missed_text, alarm.displayLabel(), time))
            .setAutoCancel(true)
            .setContentIntent(AlarmIntents.appPendingIntent(context, alarm.id))
            .build()
        manager.notify(missedId(alarm.id), notification)
    }

    /**
     * "It rang, and you were not the one who stopped it."
     *
     * Posted when a chain ends on its own, which is the alarm working as designed — so
     * this is a record, not an alert: the status channel carries no sound and no
     * vibration, because it lands at the end of the ring chain, when whoever else is in
     * the room is still asleep.
     *
     * @param ringCount rings including the first, so "rang 3 times" also answers whether
     *   the pattern was simply too gentle to wake up to.
     */
    fun showUnattended(alarm: AlarmEntity, firstRingAt: Instant, endedAt: Instant, ringCount: Int) {
        val text = context.resources.getQuantityString(
            R.plurals.notification_unattended_text,
            ringCount,
            alarm.displayLabel(),
            firstRingAt.asClockTime(),
            endedAt.asClockTime(),
            ringCount,
        )
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(context.getString(R.string.notification_unattended_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(endedAt.toEpochMilli())
            .setShowWhen(true)
            // Stays until it is dealt with: on a Shabbat morning it cannot be swiped away,
            // and it is the only evidence that the alarm did its job.
            .setAutoCancel(true)
            .setContentIntent(AlarmIntents.appPendingIntent(context, alarm.id))
            .build()
        manager.notify(unattendedId(alarm.id), notification)
    }

    /**
     * Cancels only this alarm's notifications; another alarm may be ringing.
     *
     * Includes the unattended notice: this runs when a chain ends, and the one path that
     * ends with a notice to post — auto-dismissal — posts it afterwards. Reaching here any
     * other way (the user dismissed the alarm, another alarm preempted it) means someone
     * is awake and holding the phone, so last night's notice has served its purpose.
     */
    fun cancelForAlarm(alarmId: Long) {
        manager.cancel(firingId(alarmId))
        manager.cancel(snoozedId(alarmId))
        manager.cancel(unattendedId(alarmId))
    }

    fun cancelSnoozed(alarmId: Long) = manager.cancel(snoozedId(alarmId))

    /** Called when the alarm starts ringing again, so yesterday's notice cannot linger. */
    fun cancelUnattended(alarmId: Long) = manager.cancel(unattendedId(alarmId))

    private fun AlarmEntity.displayLabel(): String =
        label.ifBlank { context.getString(R.string.default_alarm_label) }

    private fun appLocale(): Locale = context.resources.configuration.locales[0]

    /**
     * The same clock the rest of the app shows: the device's 12/24-hour setting, in the
     * app's language. A notification reading "7:30 AM" beside a screen reading "7:30"
     * sends the user looking for which of the two is wrong.
     */
    private fun Instant.asClockTime(): String =
        formatTime(context, atZone(ZoneId.systemDefault()).toLocalTime(), appLocale())

    private fun snoozedId(alarmId: Long) = SNOOZED_ID_BASE + alarmId.toInt()

    private fun missedId(alarmId: Long) = MISSED_ID_BASE + alarmId.toInt()

    private fun unattendedId(alarmId: Long) = UNATTENDED_ID_BASE + alarmId.toInt()

    companion object {
        const val CHANNEL_ALERTING = "alarm_alerting"
        const val CHANNEL_SILENT = "alarm_silent"
        const val CHANNEL_STATUS = "alarm_status"

        private const val FIRING_ID_BASE = 100_000
        private const val SNOOZED_ID_BASE = 200_000
        private const val MISSED_ID_BASE = 300_000
        private const val UNATTENDED_ID_BASE = 400_000

        /** Per-alarm id: the foreground notification must not be a shared slot. */
        fun firingId(alarmId: Long) = FIRING_ID_BASE + alarmId.toInt()
    }
}

/** Whether the platform will honour a full-screen intent for us (Android 14+ gate). */
fun Context.canUseFullScreenIntent(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
