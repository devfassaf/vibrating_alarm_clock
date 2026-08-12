package com.faybish.vibealarm.ui.alarms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.alarm.PreviewEngine
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.data.VibrationPatternEntity
import com.faybish.vibealarm.data.hasSameEditsAs
import com.faybish.vibealarm.data.withEditsFrom
import com.faybish.vibealarm.domain.NextOccurrenceCalculator
import com.faybish.vibealarm.domain.Schedule
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Alarm list state. Every write goes through [com.faybish.vibealarm.alarm.AlarmScheduler]
 * so the database and AlarmManager can never disagree about what is armed.
 *
 * An expanded card edits a [draft] rather than the row itself: changing a time is a
 * decision about tomorrow morning, and it takes an explicit save. Only the on/off switch
 * and deletion still act immediately, because those two are unambiguous the moment they
 * are tapped.
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

    private val _draft = MutableStateFlow<AlarmEntity?>(null)

    /** The alarm being edited, or null when no card is open. */
    val draft: StateFlow<AlarmEntity?> = _draft.asStateFlow()

    /** True while the open card holds edits the stored alarm has not been given yet. */
    val draftDirty: StateFlow<Boolean> = combine(_draft, alarms) { draft, stored ->
        draft != null && stored.firstOrNull { it.id == draft.id }?.hasSameEditsAs(draft) == false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // The row can move while a draft is open: the alarm rings and a one-time alarm
        // switches itself off, the pattern picker writes the chosen pattern, the switch is
        // tapped. Re-basing the draft on the fresh row keeps the card honest about all of
        // that while holding on to what the user typed.
        viewModelScope.launch {
            alarms.collect { stored ->
                val draft = _draft.value ?: return@collect
                _draft.value = stored.firstOrNull { it.id == draft.id }?.withEditsFrom(draft)
            }
        }
    }

    fun scheduleOf(alarm: AlarmEntity): Schedule = repository.scheduleOf(alarm)

    fun nextTrigger(alarm: AlarmEntity): Instant? = NextOccurrenceCalculator.nextTrigger(
        schedule = scheduleOf(alarm),
        after = Instant.now(),
        zone = ZoneId.systemDefault(),
    )

    // --- editing ---

    fun beginEdit(alarm: AlarmEntity) {
        _draft.value = alarm
    }

    fun updateDraft(edited: AlarmEntity) {
        _draft.value = edited
    }

    fun updateDraftSchedule(schedule: Schedule) {
        _draft.value = _draft.value?.let { ScheduleCodec.encode(schedule, it) }
    }

    /** Throws the edits away but stays in the editor. */
    fun resetDraft() {
        val draft = _draft.value ?: return
        _draft.value = alarms.value.firstOrNull { it.id == draft.id }
    }

    /** Closes the editor; any unsaved edits are gone. */
    fun endEdit() {
        _draft.value = null
    }

    /**
     * Writes the draft and reports what the user should be told: the saved alarm and when
     * it will ring next.
     *
     * The edits are laid onto the freshest row rather than saved as a whole entity — see
     * [withEditsFrom] — so a save cannot resurrect an alarm that finished while the card
     * was open.
     *
     * @param keepEditing true when the card stays open (the save button), false when the
     *   save is what closes it (answering the unsaved-changes question).
     */
    fun commitDraft(
        keepEditing: Boolean = true,
        onSaved: (AlarmEntity, Instant?) -> Unit = { _, _ -> },
    ) {
        val draft = _draft.value ?: return
        viewModelScope.launch {
            val fresh = repository.getAlarm(draft.id)
            if (fresh == null) {
                _draft.value = null
                return@launch
            }
            val saved = persist(fresh.withEditsFrom(draft))
            _draft.value = saved.takeIf { keepEditing }
            onSaved(saved, nextTrigger(saved))
        }
    }

    fun addAlarm(time: LocalTime, onCreated: (AlarmEntity, Instant?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val defaults = AlarmEntity(timeMinutesOfDay = ScheduleCodec.timeToMinutes(time))
            val id = repository.saveAlarm(defaults)
            val saved = repository.getAlarm(id) ?: return@launch
            scheduler.onAlarmSaved(saved)
            beginEdit(saved)
            onCreated(saved, nextTrigger(saved))
        }
    }

    fun save(alarm: AlarmEntity) {
        viewModelScope.launch { persist(alarm) }
    }

    /**
     * Rescheduling tears down the alarm's live snooze chain, so it only happens when
     * an edit actually changes when the alarm should ring. Typing a label while the
     * alarm is snoozed must not cancel the snooze.
     */
    private suspend fun persist(alarm: AlarmEntity): AlarmEntity {
        val previous = repository.getAlarm(alarm.id)
        val id = repository.saveAlarm(alarm)
        val saved = repository.getAlarm(id) ?: return alarm
        val timingChanged = previous == null ||
            previous.affectsTiming() != saved.affectsTiming()
        // An enabled alarm with no live chain is unarmed — e.g. it was saved while
        // the exact-alarm permission was still missing. Fix that on any edit.
        val unarmed = saved.enabled && repository.activeInstance(saved.id) == null
        if (timingChanged || unarmed) scheduler.onAlarmSaved(saved)
        return saved
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

    fun setEnabled(
        alarmId: Long,
        enabled: Boolean,
        onToggled: (AlarmEntity, Instant?) -> Unit = { _, _ -> },
    ) {
        viewModelScope.launch {
            scheduler.onAlarmToggled(alarmId, enabled)
            val fresh = repository.getAlarm(alarmId) ?: return@launch
            onToggled(fresh, nextTrigger(fresh))
        }
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
