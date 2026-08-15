package com.faybish.vibealarm.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmEntity::class,
        VibrationPatternEntity::class,
        AlarmInstanceEntity::class,
        ReliabilityLogEntity::class,
    ],
    version = 3,
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
            Room.databaseBuilder(context, AppDb::class.java, "alarms.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /**
         * Adds the sound ramp-up flag.
         *
         * Written by hand rather than left to a destructive fallback: the rows in this
         * database are the alarms someone is relying on tomorrow morning, and an update
         * that quietly empties the list would be discovered at the worst possible time.
         * Existing alarms default to 0 — the behaviour they already had.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE alarms ADD COLUMN soundRampUp INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds when a chain ended, and whether its morning-after notice was read.
         *
         * Both nullable, so every existing row means "ended at an unknown moment, notice
         * not acknowledged" — and a chain that finished last night before the update
         * still produces its notice instead of vanishing with the migration.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE instances ADD COLUMN endedAt INTEGER")
                db.execSQL("ALTER TABLE instances ADD COLUMN noticeAckAt INTEGER")
            }
        }
    }
}
