package com.faybish.vibealarm.ui.patterns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.alarm.VibrationEngine
import com.faybish.vibealarm.data.SegmentsCodec
import com.faybish.vibealarm.data.VibrationPatternEntity
import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.RecordedPress
import com.faybish.vibealarm.domain.RecorderQuantizer
import com.faybish.vibealarm.domain.totalDurationMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the pattern library, the segment builder and the recorder pad.
 *
 * Test playback deliberately goes through the same [VibrationEngine] call the
 * alarm uses, so what the user feels here is exactly what will wake them.
 */
class PatternViewModel(private val engine: VibrationEngine) : ViewModel() {

    private val repository = AppGraph.repository

    val patterns: StateFlow<List<VibrationPatternEntity>> = repository.observePatterns()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Working copy for the builder screen. */
    private val _draft = MutableStateFlow(PatternDraft())
    val draft: StateFlow<PatternDraft> = _draft.asStateFlow()

    val hasAmplitudeControl: Boolean get() = engine.hasAmplitudeControl

    fun loadDraft(patternId: Long?) {
        viewModelScope.launch {
            val existing = patternId?.let { repository.getPattern(it) }
            _draft.value = when {
                existing == null -> PatternDraft()
                // Presets stay pristine: editing one starts a copy.
                existing.isPreset -> PatternDraft(
                    sourceId = null,
                    name = "",
                    segments = SegmentsCodec.decode(existing.segmentsJson),
                    copiedFromPreset = existing.name,
                )

                else -> PatternDraft(
                    sourceId = existing.id,
                    name = existing.name,
                    segments = SegmentsCodec.decode(existing.segmentsJson),
                )
            }
        }
    }

    fun setDraftSegments(segments: List<PatternSegment>) {
        _draft.value = _draft.value.copy(segments = segments)
    }

    fun setDraftName(name: String) {
        _draft.value = _draft.value.copy(name = name)
    }

    fun updateSegment(index: Int, segment: PatternSegment) {
        val segments = _draft.value.segments.toMutableList()
        if (index !in segments.indices) return
        segments[index] = segment
        setDraftSegments(segments)
    }

    fun addSegment(segment: PatternSegment) {
        setDraftSegments(_draft.value.segments + segment)
    }

    fun removeSegment(index: Int) {
        val segments = _draft.value.segments.toMutableList()
        if (index !in segments.indices) return
        segments.removeAt(index)
        setDraftSegments(segments)
    }

    fun moveSegment(index: Int, delta: Int) {
        val segments = _draft.value.segments.toMutableList()
        val target = index + delta
        if (index !in segments.indices || target !in segments.indices) return
        val moved = segments.removeAt(index)
        segments.add(target, moved)
        setDraftSegments(segments)
    }

    fun applyRecording(presses: List<RecordedPress>) {
        setDraftSegments(RecorderQuantizer.quantize(presses))
    }

    fun saveDraft(onSaved: (Long) -> Unit = {}) {
        val draft = _draft.value
        if (draft.segments.isEmpty()) return
        viewModelScope.launch {
            val id = repository.savePattern(
                VibrationPatternEntity(
                    id = draft.sourceId ?: 0,
                    name = draft.effectiveName(),
                    isPreset = false,
                    segmentsJson = SegmentsCodec.encode(draft.segments),
                ),
            )
            onSaved(id)
        }
    }

    fun deletePattern(pattern: VibrationPatternEntity, onBlocked: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val inUse = repository.patternUsageCount(pattern.id)
            if (inUse > 0) {
                onBlocked(inUse)
                return@launch
            }
            repository.deletePattern(pattern)
        }
    }

    fun assignToAlarm(alarmId: Long, patternId: Long) {
        viewModelScope.launch {
            val alarm = repository.getAlarm(alarmId) ?: return@launch
            AppGraph.scheduler.onAlarmSaved(
                repository.getAlarm(repository.saveAlarm(alarm.copy(patternId = patternId)))
                    ?: return@launch,
            )
        }
    }

    // --- Playback ---

    fun test(segments: List<PatternSegment>, intensityScale: Float = 1f) {
        engine.stop()
        engine.play(
            segments = segments,
            intensityScale = intensityScale,
            repeat = false,
            forcePwmEmulation = AppGraph.settings.forcePwmEmulation,
        )
    }

    fun testStored(pattern: VibrationPatternEntity) =
        test(SegmentsCodec.decode(pattern.segmentsJson))

    fun stopTest() = engine.stop()

    fun previewAmplitude(amplitude: Int) = engine.startPreview(amplitude)

    override fun onCleared() {
        engine.stop()
        super.onCleared()
    }
}

data class PatternDraft(
    val sourceId: Long? = null,
    val name: String = "",
    val segments: List<PatternSegment> = emptyList(),
    /** Set when the draft started as a copy of a built-in pattern. */
    val copiedFromPreset: String? = null,
) {
    val totalMs: Long get() = segments.totalDurationMs

    fun effectiveName(): String = name.ifBlank { "Pattern ${System.currentTimeMillis() % 10_000}" }
}
