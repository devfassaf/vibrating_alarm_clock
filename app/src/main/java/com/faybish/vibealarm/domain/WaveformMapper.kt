package com.faybish.vibealarm.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Device-ready waveform: parallel arrays in the shape expected by
 * `VibrationEffect.createWaveform(timings, amplitudes, repeat)`.
 * [amplitudes] is always non-null; on devices without amplitude control the
 * mapper only emits 0/255 (hardware treats any non-zero as "on").
 */
class Waveform(
    val timings: LongArray,
    val amplitudes: IntArray,
    val totalMs: Long,
)

/**
 * Pure translation from pattern segments to a vibrator waveform.
 *
 * With amplitude control: one waveform entry per (merged) segment.
 * Without: intensities below [SOLID_AMPLITUDE_THRESHOLD] are emulated by
 * PWM-style on/off chopping — windows of [DEFAULT_PWM_PERIOD_MS] whose on-time
 * is proportional to the requested amplitude (never below [MIN_PWM_PULSE_MS],
 * real actuators can't produce shorter pulses reliably).
 */
object WaveformMapper {

    const val MIN_PWM_PULSE_MS = 10L
    const val DEFAULT_PWM_PERIOD_MS = 40L
    const val SOLID_AMPLITUDE_THRESHOLD = 230

    /**
     * @param intensityScale per-alarm scaling 0..1 applied to every vibrate segment.
     * @return null when the sanitized pattern is empty.
     */
    fun toWaveform(
        segments: List<PatternSegment>,
        intensityScale: Float = 1f,
        hasAmplitudeControl: Boolean,
        pwmPeriodMs: Long = DEFAULT_PWM_PERIOD_MS,
    ): Waveform? {
        val steps = sanitize(segments, intensityScale)
        if (steps.isEmpty()) return null

        val timings = ArrayList<Long>(steps.size)
        val amplitudes = ArrayList<Int>(steps.size)

        for ((durationMs, amplitude) in steps) {
            when {
                amplitude == 0 || hasAmplitudeControl ->
                    append(timings, amplitudes, durationMs, amplitude)

                amplitude >= SOLID_AMPLITUDE_THRESHOLD ->
                    append(timings, amplitudes, durationMs, PatternSegment.MAX_AMPLITUDE)

                else -> appendPwm(timings, amplitudes, durationMs, amplitude, pwmPeriodMs)
            }
        }

        return Waveform(
            timings = timings.toLongArray(),
            amplitudes = amplitudes.toIntArray(),
            totalMs = timings.sum(),
        )
    }

    /**
     * Drops non-positive durations, applies intensity scaling (clamped so a vibrate
     * segment can never scale down to silence), and merges adjacent steps that end
     * up with the same amplitude.
     */
    private fun sanitize(segments: List<PatternSegment>, intensityScale: Float): List<Step> {
        val out = ArrayList<Step>(segments.size)
        for (segment in segments) {
            if (segment.durationMs <= 0) continue
            val amplitude = when (segment.type) {
                SegmentType.PAUSE -> 0
                SegmentType.VIBRATE ->
                    (segment.amplitude.coerceIn(1, PatternSegment.MAX_AMPLITUDE) * intensityScale)
                        .roundToInt()
                        .coerceIn(1, PatternSegment.MAX_AMPLITUDE)
            }
            val last = out.lastOrNull()
            if (last != null && last.amplitude == amplitude) {
                out[out.size - 1] = Step(last.durationMs + segment.durationMs, amplitude)
            } else {
                out += Step(segment.durationMs, amplitude)
            }
        }
        // A trailing pause is pointless (the vibrator is already off afterwards).
        while (out.isNotEmpty() && out.last().amplitude == 0) out.removeAt(out.size - 1)
        return out
    }

    private fun appendPwm(
        timings: MutableList<Long>,
        amplitudes: MutableList<Int>,
        durationMs: Long,
        amplitude: Int,
        pwmPeriodMs: Long,
    ) {
        val period = pwmPeriodMs.coerceAtLeast(2 * MIN_PWM_PULSE_MS)
        val onPerPeriod = (period * amplitude / PatternSegment.MAX_AMPLITUDE.toDouble())
            .roundToLong()
            .coerceIn(MIN_PWM_PULSE_MS, period)

        var remaining = durationMs
        while (remaining > 0) {
            if (remaining <= onPerPeriod + MIN_PWM_PULSE_MS) {
                // Tail too short for a meaningful off phase — finish with an on pulse.
                append(timings, amplitudes, remaining, PatternSegment.MAX_AMPLITUDE)
                break
            }
            val window = minOf(period, remaining)
            append(timings, amplitudes, onPerPeriod, PatternSegment.MAX_AMPLITUDE)
            append(timings, amplitudes, window - onPerPeriod, 0)
            remaining -= window
        }
    }

    private fun append(
        timings: MutableList<Long>,
        amplitudes: MutableList<Int>,
        durationMs: Long,
        amplitude: Int,
    ) {
        if (durationMs <= 0) return
        val lastIndex = timings.size - 1
        if (lastIndex >= 0 && amplitudes[lastIndex] == amplitude) {
            timings[lastIndex] = timings[lastIndex] + durationMs
        } else {
            timings += durationMs
            amplitudes += amplitude
        }
    }

    private data class Step(val durationMs: Long, val amplitude: Int)
}
