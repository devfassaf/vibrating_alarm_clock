package com.faybish.vibealarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
        manager.notify(FIRING_NOTIFICATION_ID, buildFiring(alarm, instanceId))
    }

    fun showSnoozed(alarm: AlarmEntity, instanceId: Long, until: Instant, remaining: Int?) {
        val time = timeFormatter.format(until.atZone(ZoneId.systemDefault()))
        val text = if (remaining == null) {
            context.getString(R.string.notification_snoozed_until, time)
        } else {
            context.getString(R.string.notification_snoozed_until_remaining, time, remaining)
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
        val time = dateTimeFormatter.format(occurrence.atZone(ZoneId.systemDefault()))
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(context.getString(R.string.notification_missed_title))
            .setContentText(context.getString(R.string.notification_missed_text, alarm.displayLabel(), time))
            .setAutoCancel(true)
            .setContentIntent(AlarmIntents.appPendingIntent(context, alarm.id))
            .build()
        manager.notify(missedId(alarm.id), notification)
    }

    fun cancelForAlarm(alarmId: Long) {
        manager.cancel(FIRING_NOTIFICATION_ID)
        manager.cancel(snoozedId(alarmId))
    }

    fun cancelSnoozed(alarmId: Long) = manager.cancel(snoozedId(alarmId))

    private fun AlarmEntity.displayLabel(): String =
        label.ifBlank { context.getString(R.string.default_alarm_label) }

    private fun snoozedId(alarmId: Long) = SNOOZED_ID_BASE + alarmId.toInt()

    private fun missedId(alarmId: Long) = MISSED_ID_BASE + alarmId.toInt()

    companion object {
        const val CHANNEL_ALERTING = "alarm_alerting"
        const val CHANNEL_SILENT = "alarm_silent"
        const val CHANNEL_STATUS = "alarm_status"

        const val FIRING_NOTIFICATION_ID = 1001
        private const val SNOOZED_ID_BASE = 200_000
        private const val MISSED_ID_BASE = 300_000

        private val timeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        private val dateTimeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    }
}

/** Whether the platform will honour a full-screen intent for us (Android 14+ gate). */
fun Context.canUseFullScreenIntent(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
