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

/**
 * The strongest vibration this pattern asks for, which is what a single-burst preview
 * should feel like: a gentle pattern must preview gently even at full intensity, because
 * the intensity slider scales the pattern rather than replacing it.
 *
 * Falls back to full strength for a pattern with no vibration in it, so a preview always
 * gives some feedback instead of silently doing nothing.
 */
val List<PatternSegment>.peakAmplitude: Int
    get() = filter { it.type == SegmentType.VIBRATE }
        .maxOfOrNull { it.amplitude }
        ?: PatternSegment.MAX_AMPLITUDE
