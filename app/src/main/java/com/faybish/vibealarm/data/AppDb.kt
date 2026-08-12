package com.faybish.vibealarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlarmEntity::class,
        VibrationPatternEntity::class,
        AlarmInstanceEntity::class,
        ReliabilityLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun patternDao(): PatternDao
    abstract fun instanceDao(): InstanceDao
    abstract fun logDao(): LogDao

    companion object {
        /**
         * [context] MUST be the device-protected-storage context (enforced by
         * AppGraph being the only caller) so alarms remain readable after a
         * reboot, before the user first unlocks the phone.
         */
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context, AppDb::class.java, "alarms.db").build()
    }
}
