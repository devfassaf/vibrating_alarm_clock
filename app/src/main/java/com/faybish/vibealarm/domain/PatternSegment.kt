package com.faybish.vibealarm.domain

import kotlinx.serialization.Serializable

/** One step of a vibration pattern. */
@Serializable
data class PatternSegment(
    val type: SegmentType,
    val durationMs: Long,
    /** 1..255; meaningful only when [type] is [SegmentType.VIBRATE]. */
    val amplitude: Int = MAX_AMPLITUDE,
) {
    companion object {
        const val MAX_AMPLITUDE = 255

        fun vibrate(durationMs: Long, amplitude: Int = MAX_AMPLITUDE) =
            PatternSegment(SegmentType.VIBRATE, durationMs, amplitude)

        fun pause(durationMs: Long) = PatternSegment(SegmentType.PAUSE, durationMs)
    }
}

@Serializable
enum class SegmentType { VIBRATE, PAUSE }

/** Total wall time the pattern takes to play once. */
val List<PatternSegment>.totalDurationMs: Long
    get() = sumOf { it.durationMs }
