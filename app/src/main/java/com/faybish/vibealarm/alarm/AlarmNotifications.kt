package com.faybish.vibealarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.ui.format.NoticeText
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
        val text = NoticeText.neverRangDetail(context, alarm.label, occurrence)
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(NoticeText.neverRangTitle(context))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
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
     * The title leads with the miss and the time — "you missed an alarm at 07:30" — because
     * that is the sentence someone reads half awake and needs no second thought about. The
     * detail line carries how many times it tried, which is the part that answers whether the
     * pattern was simply too gentle to wake up to.
     *
     * @param ringCount rings including the first.
     */
    fun showUnattended(alarm: AlarmEntity, firstRingAt: Instant, endedAt: Instant, ringCount: Int) {
        val text = NoticeText.unattendedDetail(
            context = context,
            label = alarm.label,
            firstRingAt = firstRingAt,
            endedAt = endedAt,
            ringCount = ringCount,
        )
        val notification = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(NoticeText.unattendedTitle(context, firstRingAt))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(endedAt.toEpochMilli())
            .setShowWhen(true)
            // Tapping it opens the app, where the same sentence is waiting as a banner —
            // so clearing the notice never costs the user the chance to read it. Samsung
            // shows notifications as a two-second pill by default, which is not long
            // enough to take in a sentence with a time and a count in it.
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
        cancelNotices(alarmId)
    }

    fun cancelSnoozed(alarmId: Long) = manager.cancel(snoozedId(alarmId))

    /**
     * Both morning-after notices, together.
     *
     * They are cancelled as a pair because they are read as a pair: each one puts the same
     * red dot on the launcher icon, and a user clearing "it rang and you slept through it"
     * while last week's "it never rang" stays behind is left with a dot that the app has
     * already stopped explaining.
     */
    fun cancelNotices(alarmId: Long) {
        manager.cancel(unattendedId(alarmId))
        manager.cancel(missedId(alarmId))
    }

    private fun AlarmEntity.displayLabel(): String =
        label.ifBlank { context.getString(R.string.default_alarm_label) }

    private fun appLocale(): Locale = context.resources.configuration.locales[0]

    /** The same clock the rest of the app shows: see [NoticeText]. */
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
