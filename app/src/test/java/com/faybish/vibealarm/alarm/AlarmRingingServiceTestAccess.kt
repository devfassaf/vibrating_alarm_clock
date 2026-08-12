package com.faybish.vibealarm.alarm

/**
 * Lets a test pretend an alarm is ringing.
 *
 * `AlarmRingingService.playingAlarmId` has a private setter on purpose — only the service
 * may claim to be playing — so this sits in the same package to reach it, rather than
 * widening the production API for a test.
 */
internal object AlarmRingingServiceTestAccess {
    fun setPlaying(alarmId: Long?) {
        val field = AlarmRingingService::class.java.getDeclaredField("playingAlarmId")
        field.isAccessible = true
        field.set(null, alarmId)
    }
}
