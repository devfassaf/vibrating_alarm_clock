package com.faybish.vibealarm.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ScheduleType {
    const val ONE_TIME = 0
    const val WEEKLY = 1
    const val DATES = 2
}

object RingMode {
    const val SOUND = 0
    const val VIBRATE_ONLY = 1
}

object BackgroundType {
    const val COLOR = 0
    const val IMAGE = 1
}

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String = "",
    val enabled: Boolean = true,

    // Schedule (see ScheduleCodec)
    val scheduleType: Int = ScheduleType.ONE_TIME,
    val timeMinutesOfDay: Int,
    /** Weekly only: bit(dayOfWeek.value - 1); Monday = bit 0 ... Sunday = bit 6. */
    val daysBitmask: Int = 0,
    /** Weekly only: JSON Map<Int isoDay, Int minutesOfDay> of per-day time overrides. */
    val perDayOverridesJson: String? = null,
    /** Dates only: JSON List<Long> of epoch days. */
    val datesJson: String? = null,

    // Ringing
    val mode: Int = RingMode.VIBRATE_ONLY,
    val ringtoneUri: String? = null,
    /** 0..1 relative to the alarm stream's max volume. */
    val volume: Float = 0.8f,
    val vibrateWithSound: Boolean = true,
    val patternId: Long? = null,
    /** 0.1..1 scaling applied to every vibrate segment's amplitude. */
    val intensityScale: Float = 1f,
    /** false = Shabbat mode: no full-screen UI, screen stays dark, vibration only. */
    val turnScreenOn: Boolean = true,
    /** Sound mode: how long the ringtone plays before auto-snooze/dismiss. */
    val autoSilenceSeconds: Int = 60,
    /** Sound mode: start quiet and climb to [volume] instead of opening at full level. */
    val soundRampUp: Boolean = false,

    // Snooze
    val snoozeIntervalMinutes: Int = 5,
    /** -1 = until dismissed. */
    val snoozeRepeatCount: Int = 3,

    // Ringing-screen looks
    val backgroundType: Int = BackgroundType.COLOR,
    val backgroundColorArgb: Int = 0xFF111318.toInt(),
    val backgroundImagePath: String? = null,

    /** null = follow the global setting. */
    val volumeKeysSnooze: Boolean? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "patterns")
data class VibrationPatternEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isPreset: Boolean = false,
    /** JSON List<PatternSegment>. */
    val segmentsJson: String,
)

object InstanceState {
    const val SCHEDULED = 0
    const val FIRING = 1
    const val SNOOZED = 2
    const val DONE = 3
}

object EndedReason {
    const val AUTO_DISMISSED = 0
    const val USER_DISMISSED = 1
    const val MISSED = 2
    const val PREEMPTED = 3

    /**
     * The endings the user is told about the next morning. USER_DISMISSED is absent on
     * purpose: they were there, they switched it off, and there is nothing to report.
     * Must stay the exact set `noticeKindOf` maps to a kind — MissedNoticesTest pins the
     * two together, because a reason in one list and not the other is a red dot on the
     * launcher whose row the banner never renders.
     */
    val NOTICE_WORTHY = listOf(AUTO_DISMISSED, MISSED, PREEMPTED)
}

/**
 * One occurrence's ring/snooze chain (see AlarmSessionReducer). Invariant:
 * at most one non-DONE instance per alarm.
 */
@Entity(
    tableName = "instances",
    foreignKeys = [
        ForeignKey(
            entity = AlarmEntity::class,
            parentColumns = ["id"],
            childColumns = ["alarmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("alarmId")],
)
data class AlarmInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: Long,
    val occurrenceEpochMillis: Long,
    val state: Int = InstanceState.SCHEDULED,
    val snoozesUsed: Int = 0,
    /** The trigger currently armed: the occurrence itself, or snooze-until. */
    val nextActionEpochMillis: Long,
    val firedAt: Long? = null,
    val endedReason: Int? = null,
    /** When the chain actually stopped — the "until 07:40" in the morning-after notice. */
    val endedAt: Long? = null,
    /**
     * When the user acknowledged the notice this row produced, or null while it is still
     * waiting to be read. Persisted rather than kept in memory because the whole point of
     * the notice is to survive the night, the reboot, and the app being swiped away.
     */
    val noticeAckAt: Long? = null,
)

@Entity(tableName = "reliability_log")
data class ReliabilityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val event: String,
    val detail: String = "",
)

/**
 * Writes an [com.faybish.vibealarm.domain.AlertSelection] back to the two columns that
 * have always encoded it. Kept here so the mapping lives next to the columns it targets.
 */
fun AlarmEntity.applying(alert: com.faybish.vibealarm.domain.AlertSelection): AlarmEntity = copy(
    mode = if (alert.storedAsSoundMode) RingMode.SOUND else RingMode.VIBRATE_ONLY,
    vibrateWithSound = alert.storedVibrateWithSound,
)
