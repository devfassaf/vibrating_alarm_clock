package com.faybish.vibealarm.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rows in this database are the alarms someone is relying on tomorrow morning, so an
 * app update must not empty the list — or refuse to open at all. This builds a real
 * version-1 file the way a shipped install has it, then opens it with the current schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDbMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
    }

    /** The schema exactly as version 1 shipped it (app/schemas/…/1.json). */
    private fun createVersion1Database() {
        val file = context.getDatabasePath(DB_NAME)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        listOf(
            "CREATE TABLE IF NOT EXISTS `alarms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `label` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                "`scheduleType` INTEGER NOT NULL, `timeMinutesOfDay` INTEGER NOT NULL, " +
                "`daysBitmask` INTEGER NOT NULL, `perDayOverridesJson` TEXT, `datesJson` TEXT, " +
                "`mode` INTEGER NOT NULL, `ringtoneUri` TEXT, `volume` REAL NOT NULL, " +
                "`vibrateWithSound` INTEGER NOT NULL, `patternId` INTEGER, " +
                "`intensityScale` REAL NOT NULL, `turnScreenOn` INTEGER NOT NULL, " +
                "`autoSilenceSeconds` INTEGER NOT NULL, `snoozeIntervalMinutes` INTEGER NOT NULL, " +
                "`snoozeRepeatCount` INTEGER NOT NULL, `backgroundType` INTEGER NOT NULL, " +
                "`backgroundColorArgb` INTEGER NOT NULL, `backgroundImagePath` TEXT, " +
                "`volumeKeysSnooze` INTEGER, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `patterns` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `name` TEXT NOT NULL, `isPreset` INTEGER NOT NULL, " +
                "`segmentsJson` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `instances` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                "NOT NULL, `alarmId` INTEGER NOT NULL, `occurrenceEpochMillis` INTEGER NOT NULL, " +
                "`state` INTEGER NOT NULL, `snoozesUsed` INTEGER NOT NULL, " +
                "`nextActionEpochMillis` INTEGER NOT NULL, `firedAt` INTEGER, " +
                "`endedReason` INTEGER, FOREIGN KEY(`alarmId`) REFERENCES `alarms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_instances_alarmId` ON `instances` (`alarmId`)",
            "CREATE TABLE IF NOT EXISTS `reliability_log` (`id` INTEGER PRIMARY KEY " +
                "AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `event` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, " +
                "identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                "VALUES(42, '$VERSION_1_IDENTITY_HASH')",
            // A Shabbat alarm: weekly, vibration only, screen stays dark.
            "INSERT INTO alarms (id, label, enabled, scheduleType, timeMinutesOfDay, " +
                "daysBitmask, mode, volume, vibrateWithSound, intensityScale, turnScreenOn, " +
                "autoSilenceSeconds, snoozeIntervalMinutes, snoozeRepeatCount, backgroundType, " +
                "backgroundColorArgb, createdAt, updatedAt) VALUES " +
                "(7, 'שבת', 1, 1, 450, 32, 1, 0.8, 1, 0.6, 0, 60, 3, 2, 0, -1, 100, 200)",
            // A chain that rang twice and ended by itself — the morning before the update.
            "INSERT INTO instances (id, alarmId, occurrenceEpochMillis, state, snoozesUsed, " +
                "nextActionEpochMillis, firedAt, endedReason) VALUES " +
                "(3, 7, 1000, 3, 1, 2000, 1000, 0)",
        ).forEach(db::execSQL)
        db.version = 1
        db.close()
    }

    private fun openCurrent(withMigration: Boolean = true): AppDb =
        Room.databaseBuilder(context, AppDb::class.java, DB_NAME)
            .allowMainThreadQueries()
            .apply {
                if (withMigration) addMigrations(AppDb.MIGRATION_1_2, AppDb.MIGRATION_2_3)
            }
            .build()

    @Test
    fun `an alarm saved by version 1 survives the upgrade untouched`() = runBlocking {
        createVersion1Database()

        val db = openCurrent()
        val alarm = db.alarmDao().getById(7)
        db.close()

        assertThat(alarm).isNotNull()
        assertThat(alarm!!.label).isEqualTo("שבת")
        assertThat(alarm.enabled).isTrue()
        assertThat(alarm.scheduleType).isEqualTo(ScheduleType.WEEKLY)
        assertThat(alarm.timeMinutesOfDay).isEqualTo(450)
        assertThat(alarm.daysBitmask).isEqualTo(32)
        assertThat(alarm.mode).isEqualTo(RingMode.VIBRATE_ONLY)
        assertThat(alarm.intensityScale).isEqualTo(0.6f)
        assertThat(alarm.turnScreenOn).isFalse()
        assertThat(alarm.snoozeRepeatCount).isEqualTo(2)
    }

    /** New behaviour must be opt-in: an existing alarm keeps sounding the way it did. */
    @Test
    fun `the new ramp-up flag defaults to off for alarms that predate it`() = runBlocking {
        createVersion1Database()

        val db = openCurrent()
        val alarm = db.alarmDao().getById(7)
        db.close()

        assertThat(alarm!!.soundRampUp).isFalse()
    }

    @Test
    fun `alarms saved after the upgrade round-trip through the new column`() = runBlocking {
        createVersion1Database()

        val db = openCurrent()
        val id = db.alarmDao().upsert(
            AlarmEntity(timeMinutesOfDay = 400, soundRampUp = true, autoSilenceSeconds = 45),
        )
        val saved = db.alarmDao().getById(id)
        db.close()

        assertThat(saved!!.soundRampUp).isTrue()
        assertThat(saved.autoSilenceSeconds).isEqualTo(45)
    }

    /**
     * The notice is the app's only account of a morning that went wrong, and the update
     * lands the day after such a morning as often as any other. Both new columns are
     * nullable so the row reads as "ended at an unknown moment, notice not read yet" —
     * an upgrade that silently marked it read would delete the evidence.
     */
    @Test
    fun `a chain that ended before the upgrade keeps its unread notice`() = runBlocking {
        createVersion1Database()

        val db = openCurrent()
        val instance = db.instanceDao().getById(3)
        db.close()

        assertThat(instance).isNotNull()
        assertThat(instance!!.endedReason).isEqualTo(EndedReason.AUTO_DISMISSED)
        assertThat(instance.snoozesUsed).isEqualTo(1)
        assertThat(instance.endedAt).isNull()
        assertThat(instance.noticeAckAt).isNull()
    }

    @Test
    fun `acknowledging a notice writes through the new column`() = runBlocking {
        createVersion1Database()

        val db = openCurrent()
        db.instanceDao().acknowledgeNoticesOf(alarmId = 7, at = 555)
        val acknowledged = db.instanceDao().getById(3)
        db.close()

        assertThat(acknowledged!!.noticeAckAt).isEqualTo(555)
    }

    /**
     * Guards the migration itself rather than the column: without it Room refuses to open
     * the file, which on a shipped app is a crash on launch after an update.
     */
    @Test
    fun `without the migration the upgrade would fail loudly instead of silently`() {
        createVersion1Database()

        val failure = runCatching { openCurrent(withMigration = false).openHelper.readableDatabase }
        assertThat(failure.isFailure).isTrue()
    }

    private companion object {
        const val DB_NAME = "alarms.db"

        /** From app/schemas/com.faybish.vibealarm.data.AppDb/1.json. */
        const val VERSION_1_IDENTITY_HASH = "3d9dedbde96fbadcdf7b65b1597bf6c5"
    }
}
