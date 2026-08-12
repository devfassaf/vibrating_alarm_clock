package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.Schedule
import kotlinx.coroutines.flow.Flow

/**
 * Thin persistence facade for alarms and patterns. Scheduling side effects
 * (re-arming AlarmManager after writes) belong to the alarm layer, which wraps
 * these calls — see AlarmScheduler.
 */
class AlarmRepository(private val db: AppDb) {

    private val alarmDao = db.alarmDao()
    private val patternDao = db.patternDao()
    private val instanceDao = db.instanceDao()

    // --- Alarms ---

    fun observeAlarms(): Flow<List<AlarmEntity>> = alarmDao.observeAll()

    suspend fun getAlarm(id: Long): AlarmEntity? = alarmDao.getById(id)

    suspend fun getEnabledAlarms(): List<AlarmEntity> = alarmDao.getEnabled()

    suspend fun saveAlarm(alarm: AlarmEntity): Long {
        val saved = alarm.copy(updatedAt = System.currentTimeMillis())
        val id = alarmDao.upsert(saved)
        return if (id == -1L) alarm.id else id
    }

    suspend fun deleteAlarm(id: Long) = alarmDao.delete(id)

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) {
        alarmDao.setEnabled(id, enabled)
        if (!enabled) instanceDao.deleteActiveForAlarm(id)
    }

    fun scheduleOf(alarm: AlarmEntity): Schedule = ScheduleCodec.decode(alarm)

    // --- Patterns ---

    fun observePatterns(): Flow<List<VibrationPatternEntity>> = patternDao.observeAll()

    suspend fun getPattern(id: Long): VibrationPatternEntity? = patternDao.getById(id)

    /** Segments for an alarm, falling back to the built-in default — never empty. */
    suspend fun segmentsForAlarm(alarm: AlarmEntity): List<PatternSegment> {
        val pattern = alarm.patternId?.let { patternDao.getById(it) }
        val segments = pattern?.let { SegmentsCodec.decode(it.segmentsJson) }
        return if (segments.isNullOrEmpty()) PresetPatterns.DEFAULT_SEGMENTS else segments
    }

    suspend fun savePattern(pattern: VibrationPatternEntity): Long {
        val id = patternDao.upsert(pattern)
        return if (id == -1L) pattern.id else id
    }

    suspend fun deletePattern(pattern: VibrationPatternEntity) = patternDao.delete(pattern)

    suspend fun patternUsageCount(patternId: Long): Int = patternDao.usageCount(patternId)

    suspend fun ensurePresetsSeeded() = patternDao.insertIgnoring(PresetPatterns.all)

    // --- Instances ---

    suspend fun activeInstance(alarmId: Long): AlarmInstanceEntity? =
        instanceDao.getActiveForAlarm(alarmId)

    suspend fun allActiveInstances(): List<AlarmInstanceEntity> = instanceDao.getAllActive()

    suspend fun getInstance(id: Long): AlarmInstanceEntity? = instanceDao.getById(id)

    suspend fun saveInstance(instance: AlarmInstanceEntity): Long {
        val id = instanceDao.upsert(instance)
        return if (id == -1L) instance.id else id
    }

    suspend fun clearActiveInstance(alarmId: Long) = instanceDao.deleteActiveForAlarm(alarmId)

    suspend fun pruneOldInstances() =
        instanceDao.pruneDone(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
}
