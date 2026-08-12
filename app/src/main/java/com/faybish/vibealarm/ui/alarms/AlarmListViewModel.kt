package com.faybish.vibealarm.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.alarm.PreviewEngine
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

    /**
     * Previews for the volume and intensity sliders. Held here so it is torn down with
     * the screen: a preview left running would keep the vibrator going and would leave
     * the user's alarm stream volume where this engine put it.
     */
    private val preview = PreviewEngine(
        context = AppGraph.deviceProtectedContext,
        scope = viewModelScope,
        logger = AppGraph.reliabilityLogger,
    )

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
            val timingChanged = previous == null ||
                previous.affectsTiming() != saved.affectsTiming()
            // An enabled alarm with no live chain is unarmed — e.g. it was saved while
            // the exact-alarm permission was still missing. Fix that on any edit.
            val unarmed = saved.enabled && repository.activeInstance(saved.id) == null
            if (timingChanged || unarmed) scheduler.onAlarmSaved(saved)
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

    /** Feels the alarm's own pattern at the currently chosen intensity. */
    fun previewVibration(alarm: AlarmEntity) {
        viewModelScope.launch {
            preview.previewVibration(
                segments = repository.segmentsForAlarm(alarm),
                intensityScale = alarm.intensityScale,
                forcePwmEmulation = AppGraph.settings.forcePwmEmulation,
            )
        }
    }

    /** Plays the alarm's own ringtone at the currently chosen volume. */
    fun previewSound(alarm: AlarmEntity) {
        preview.previewSound(alarm.ringtoneUri, alarm.volume)
    }

    override fun onCleared() {
        preview.stop()
        super.onCleared()
    }
}
