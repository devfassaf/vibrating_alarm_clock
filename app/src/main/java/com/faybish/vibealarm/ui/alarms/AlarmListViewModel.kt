package com.faybish.vibealarm.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.data.VibrationPatternEntity
import com.faybish.vibealarm.domain.NextOccurrenceCalculator
import com.faybish.vibealarm.domain.Schedule
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Alarm list state. Every write goes through [com.faybish.vibealarm.alarm.AlarmScheduler]
 * so the database and AlarmManager can never disagree about what is armed.
 */
class AlarmListViewModel : ViewModel() {

    private val repository = AppGraph.repository
    private val scheduler = AppGraph.scheduler

    val alarms: StateFlow<List<AlarmEntity>> = repository.observeAlarms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val patterns: StateFlow<List<VibrationPatternEntity>> = repository.observePatterns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val patternNames: StateFlow<Map<Long, String>> = repository.observePatterns()
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun scheduleOf(alarm: AlarmEntity): Schedule = repository.scheduleOf(alarm)

    fun nextTrigger(alarm: AlarmEntity): Instant? = NextOccurrenceCalculator.nextTrigger(
        schedule = scheduleOf(alarm),
        after = Instant.now(),
        zone = ZoneId.systemDefault(),
    )

    fun addAlarm(time: LocalTime, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val defaults = AlarmEntity(timeMinutesOfDay = ScheduleCodec.timeToMinutes(time))
            val id = repository.saveAlarm(defaults)
            val saved = repository.getAlarm(id) ?: return@launch
            scheduler.onAlarmSaved(saved)
            onCreated(id)
        }
    }

    /**
     * Rescheduling tears down the alarm's live snooze chain, so it only happens when
     * an edit actually changes when the alarm should ring. Typing a label while the
     * alarm is snoozed must not cancel the snooze.
     */
    fun save(alarm: AlarmEntity) {
        viewModelScope.launch {
            val previous = repository.getAlarm(alarm.id)
            val id = repository.saveAlarm(alarm)
            val saved = repository.getAlarm(id) ?: return@launch
            if (previous == null || previous.affectsTiming() != saved.affectsTiming()) {
                scheduler.onAlarmSaved(saved)
            }
        }
    }

    /** The fields that determine when the alarm fires next. */
    private fun AlarmEntity.affectsTiming() = listOf(
        enabled,
        scheduleType,
        timeMinutesOfDay,
        daysBitmask,
        perDayOverridesJson,
        datesJson,
    )

    fun updateSchedule(alarm: AlarmEntity, schedule: Schedule) {
        save(ScheduleCodec.encode(schedule, alarm))
    }

    fun setEnabled(alarmId: Long, enabled: Boolean) {
        viewModelScope.launch { scheduler.onAlarmToggled(alarmId, enabled) }
    }

    fun delete(alarmId: Long) {
        viewModelScope.launch { scheduler.onAlarmDeleted(alarmId) }
    }
}
