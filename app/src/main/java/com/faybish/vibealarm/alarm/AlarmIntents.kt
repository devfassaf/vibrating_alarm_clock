package com.faybish.vibealarm.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.faybish.vibealarm.AlarmActivity
import com.faybish.vibealarm.MainActivity

/**
 * Intent plumbing for the alarm pipeline.
 *
 * PendingIntent identity ignores extras ([Intent.filterEquals]), so each alarm
 * gets its own data URI and request code. That guarantees exactly one armed
 * trigger per alarm: a snooze re-arm updates the same slot instead of stacking.
 */
object AlarmIntents {

    const val ACTION_FIRE = "com.faybish.vibealarm.action.FIRE"
    const val ACTION_SNOOZE = "com.faybish.vibealarm.action.SNOOZE"
    const val ACTION_DISMISS = "com.faybish.vibealarm.action.DISMISS"
    const val ACTION_START_RINGING = "com.faybish.vibealarm.action.START_RINGING"
    const val ACTION_STOP_RINGING = "com.faybish.vibealarm.action.STOP_RINGING"

    const val EXTRA_ALARM_ID = "alarmId"
    const val EXTRA_INSTANCE_ID = "instanceId"

    private const val REQUEST_STRIDE = 10
    private const val OFFSET_FIRE = 0
    private const val OFFSET_SNOOZE = 1
    private const val OFFSET_DISMISS = 2
    private const val OFFSET_SHOW = 3
    private const val OFFSET_CONTENT = 4

    private fun requestCode(alarmId: Long, offset: Int): Int =
        (alarmId.toInt() * REQUEST_STRIDE) + offset

    private fun dataUri(alarmId: Long, suffix: String): Uri =
        Uri.parse("vibealarm://alarm/$alarmId/$suffix")

    /** The exact-alarm trigger. Cancelling this cancels the alarm. */
    fun firePendingIntent(context: Context, alarmId: Long, instanceId: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = dataUri(alarmId, "fire")
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_INSTANCE_ID, instanceId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId, OFFSET_FIRE),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Same identity as [firePendingIntent] with no extras — used only to cancel,
     * so it must never create a live PendingIntent of its own.
     */
    fun cancelFirePendingIntent(context: Context, alarmId: Long): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            data = dataUri(alarmId, "fire")
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId, OFFSET_FIRE),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun snoozePendingIntent(context: Context, alarmId: Long, instanceId: Long): PendingIntent =
        actionBroadcast(context, ACTION_SNOOZE, "snooze", OFFSET_SNOOZE, alarmId, instanceId)

    fun dismissPendingIntent(context: Context, alarmId: Long, instanceId: Long): PendingIntent =
        actionBroadcast(context, ACTION_DISMISS, "dismiss", OFFSET_DISMISS, alarmId, instanceId)

    /**
     * Full-screen / content intent. Always an activity PendingIntent: launching an
     * activity from a notification via a receiver or service is blocked on Android 12+.
     */
    fun ringingActivityIntent(context: Context, alarmId: Long, instanceId: Long): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            data = dataUri(alarmId, "ring")
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_INSTANCE_ID, instanceId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(alarmId, OFFSET_SHOW),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Lock-screen "next alarm" tap target and the snoozed notification's body tap. */
    fun appPendingIntent(context: Context, alarmId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = dataUri(alarmId, "open")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            requestCode(alarmId, OFFSET_CONTENT),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun startRingingService(context: Context, alarmId: Long, instanceId: Long): Intent =
        Intent(context, AlarmRingingService::class.java).apply {
            action = ACTION_START_RINGING
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_INSTANCE_ID, instanceId)
        }

    private fun actionBroadcast(
        context: Context,
        action: String,
        suffix: String,
        offset: Int,
        alarmId: Long,
        instanceId: Long,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            data = dataUri(alarmId, suffix)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_INSTANCE_ID, instanceId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId, offset),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
