package com.faybish.vibealarm.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY timeMinutesOfDay, id")
    fun observeAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms")
    suspend fun getAll(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE enabled = 1")
    suspend fun getEnabled(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: Long): AlarmEntity?

    @Upsert
    suspend fun upsert(alarm: AlarmEntity): Long

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE alarms SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long = System.currentTimeMillis())
}

@Dao
interface PatternDao {
    @Query("SELECT * FROM patterns ORDER BY isPreset DESC, name")
    fun observeAll(): Flow<List<VibrationPatternEntity>>

    @Query("SELECT * FROM patterns WHERE id = :id")
    suspend fun getById(id: Long): VibrationPatternEntity?

    @Upsert
    suspend fun upsert(pattern: VibrationPatternEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(patterns: List<VibrationPatternEntity>)

    @Delete
    suspend fun delete(pattern: VibrationPatternEntity)

    @Query("SELECT COUNT(*) FROM alarms WHERE patternId = :patternId")
    suspend fun usageCount(patternId: Long): Int
}

@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances WHERE alarmId = :alarmId AND state != 3 ORDER BY id DESC LIMIT 1")
    suspend fun getActiveForAlarm(alarmId: Long): AlarmInstanceEntity?

    @Query("SELECT * FROM instances WHERE alarmId = :alarmId AND state != 3 ORDER BY id DESC LIMIT 1")
    fun observeActiveForAlarm(alarmId: Long): Flow<AlarmInstanceEntity?>

    @Query("SELECT * FROM instances WHERE state != 3")
    suspend fun getAllActive(): List<AlarmInstanceEntity>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun getById(id: Long): AlarmInstanceEntity?

    @Upsert
    suspend fun upsert(instance: AlarmInstanceEntity): Long

    @Query("DELETE FROM instances WHERE alarmId = :alarmId AND state != 3")
    suspend fun deleteActiveForAlarm(alarmId: Long)

    @Query("DELETE FROM instances WHERE state = 3 AND occurrenceEpochMillis < :olderThan")
    suspend fun pruneDone(olderThan: Long)
}

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: ReliabilityLogEntity)

    @Query("SELECT * FROM reliability_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<ReliabilityLogEntity>>

    @Query("SELECT * FROM reliability_log WHERE event = :event ORDER BY id DESC LIMIT 1")
    suspend fun latest(event: String): ReliabilityLogEntity?

    @Query("DELETE FROM reliability_log WHERE timestamp < :olderThan")
    suspend fun pruneOlderThan(olderThan: Long)
}
