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

    companion object {
        /** Chains that ended in a way the user should hear about and has not acknowledged. */
        const val UNREAD_NOTICES = "SELECT * FROM instances WHERE state = 3 " +
            "AND noticeAckAt IS NULL AND endedReason IN (:reasons) " +
            "ORDER BY occurrenceEpochMillis DESC"
    }

    @Query("SELECT * FROM instances WHERE alarmId = :alarmId AND state != 3 ORDER BY id DESC LIMIT 1")
    suspend fun getActiveForAlarm(alarmId: Long): AlarmInstanceEntity?

    @Query("SELECT * FROM instances WHERE alarmId = :alarmId AND state != 3 ORDER BY id DESC LIMIT 1")
    fun observeActiveForAlarm(alarmId: Long): Flow<AlarmInstanceEntity?>

    @Query("SELECT * FROM instances WHERE state != 3")
    suspend fun getAllActive(): List<AlarmInstanceEntity>

    /** Watched by the list screen, which offers to call off a snooze before it rings. */
    @Query("SELECT * FROM instances WHERE state = 2 ORDER BY nextActionEpochMillis")
    fun observeSnoozed(): Flow<List<AlarmInstanceEntity>>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun getById(id: Long): AlarmInstanceEntity?

    /**
     * Chains that ended in a way the user should hear about and has not acknowledged yet.
     * Watched by the list screen, so the launcher's red dot always has something inside
     * the app that explains it.
     */
    @Query(UNREAD_NOTICES)
    fun observeUnreadNotices(reasons: List<Int>): Flow<List<AlarmInstanceEntity>>

    /**
     * The same query as [observeUnreadNotices], for callers that cannot collect a Flow —
     * the pipeline test runs on a paused dispatcher where a Room Flow never emits. Sharing
     * the string is what keeps the banner and the assertions describing one set of rows.
     */
    @Query(UNREAD_NOTICES)
    suspend fun unreadNotices(reasons: List<Int>): List<AlarmInstanceEntity>

    /**
     * Every unread notice of one alarm, because the notification is per-alarm: two unread
     * rows share one slot and one red dot, so acknowledging half of them would leave a
     * banner standing with no dot behind it.
     */
    @Query(
        "UPDATE instances SET noticeAckAt = :at WHERE alarmId = :alarmId AND noticeAckAt IS NULL",
    )
    suspend fun acknowledgeNoticesOf(alarmId: Long, at: Long)

    /**
     * Notices produced since [since] — used when the user switches an alarm off mid-ring.
     * That chain may still commit its ending and report a notice for a morning the user
     * has just cancelled by hand, while notices from earlier mornings stay unread.
     */
    @Query(
        "UPDATE instances SET noticeAckAt = :at WHERE alarmId = :alarmId " +
            "AND noticeAckAt IS NULL AND endedAt IS NOT NULL AND endedAt >= :since",
    )
    suspend fun acknowledgeNoticesSince(alarmId: Long, since: Long, at: Long): Int

    /**
     * Used when the alarm rings again: last night's notice is no longer the news.
     *
     * Restricted to chains that have already ended, because this runs from inside a ring —
     * without it the live chain stamps its own row read before it has anything to report,
     * and the notice it goes on to produce is invisible from the moment it is created.
     */
    @Query(
        "UPDATE instances SET noticeAckAt = :at " +
            "WHERE alarmId = :alarmId AND noticeAckAt IS NULL AND state = 3",
    )
    suspend fun acknowledgeNoticesFor(alarmId: Long, at: Long)

    @Upsert
    suspend fun upsert(instance: AlarmInstanceEntity): Long

    @Query("DELETE FROM instances WHERE alarmId = :alarmId AND state != 3")
    suspend fun deleteActiveForAlarm(alarmId: Long)

    /**
     * Old finished chains, except any whose morning-after notice is still unread: pruning one
     * of those would leave the notification and the launcher's red dot with nothing behind
     * them in the app, which is the state invariant 14 exists to prevent.
     */
    @Query(
        "DELETE FROM instances WHERE state = 3 AND occurrenceEpochMillis < :olderThan " +
            "AND (noticeAckAt IS NOT NULL OR endedReason NOT IN (:noticeReasons))",
    )
    suspend fun pruneDone(olderThan: Long, noticeReasons: List<Int>)
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
