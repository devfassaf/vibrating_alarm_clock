package com.faybish.vibealarm.domain

import com.faybish.vibealarm.domain.PatternSegment.Companion.pause
import com.faybish.vibealarm.domain.PatternSegment.Companion.vibrate
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class WaveformMapperTest {

    // --- Amplitude-control path ---

    @Test
    fun `amplitude path maps segments one to one`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(500, 100), pause(200), vibrate(300, 255)),
            hasAmplitudeControl = true,
        )!!
        assertThat(waveform.timings.toList()).containsExactly(500L, 200L, 300L).inOrder()
        assertThat(waveform.amplitudes.toList()).containsExactly(100, 0, 255).inOrder()
        assertThat(waveform.totalMs).isEqualTo(1000)
    }

    @Test
    fun `intensity scale shrinks amplitudes but never to zero`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(500, 200), pause(100), vibrate(500, 10)),
            intensityScale = 0.05f,
            hasAmplitudeControl = true,
        )!!
        assertThat(waveform.amplitudes[0]).isEqualTo(10) // 200 * 0.05
        assertThat(waveform.amplitudes[1]).isEqualTo(0) // pause untouched
        assertThat(waveform.amplitudes[2]).isEqualTo(1) // 10 * 0.05 -> clamped to 1
    }

    @Test
    fun `adjacent equal-amplitude steps merge`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(100, 80), vibrate(200, 80), pause(50), pause(70), vibrate(30, 90)),
            hasAmplitudeControl = true,
        )!!
        assertThat(waveform.timings.toList()).containsExactly(300L, 120L, 30L).inOrder()
        assertThat(waveform.amplitudes.toList()).containsExactly(80, 0, 90).inOrder()
    }

    @Test
    fun `zero and negative durations are dropped`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(0, 100), vibrate(-50, 100), vibrate(400, 100)),
            hasAmplitudeControl = true,
        )!!
        assertThat(waveform.timings.toList()).containsExactly(400L)
    }

    @Test
    fun `trailing pauses are trimmed`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(400, 100), pause(500), pause(300)),
            hasAmplitudeControl = true,
        )!!
        assertThat(waveform.timings.toList()).containsExactly(400L)
        assertThat(waveform.totalMs).isEqualTo(400)
    }

    @Test
    fun `empty or pause-only patterns yield null`() {
        assertThat(WaveformMapper.toWaveform(emptyList(), hasAmplitudeControl = true)).isNull()
        assertThat(
            WaveformMapper.toWaveform(listOf(pause(500)), hasAmplitudeControl = true),
        ).isNull()
    }

    @Test
    fun `leading pause is preserved`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(pause(250), vibrate(400, 100)),
            hasAmplitudeControl = true,
        )!!
        assertThat(waveform.timings.toList()).containsExactly(250L, 400L).inOrder()
        assertThat(waveform.amplitudes.toList()).containsExactly(0, 100).inOrder()
    }

    // --- PWM emulation path (no amplitude control) ---

    @Test
    fun `pwm emits only full-on and off amplitudes`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(1000, 128), pause(200), vibrate(300, 40)),
            hasAmplitudeControl = false,
        )!!
        assertThat(waveform.amplitudes.toSet()).containsExactly(0, 255)
    }

    @Test
    fun `pwm preserves total duration`() {
        val segments = listOf(vibrate(1000, 128), pause(200), vibrate(333, 77), vibrate(90, 20))
        val waveform = WaveformMapper.toWaveform(segments, hasAmplitudeControl = false)!!
        assertThat(waveform.totalMs).isEqualTo(segments.totalDurationMs)
        assertThat(waveform.timings.sum()).isEqualTo(waveform.totalMs)
    }

    @Test
    fun `pwm duty cycle approximates requested amplitude`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(2000, 128)),
            hasAmplitudeControl = false,
        )!!
        var on = 0L
        for (i in waveform.timings.indices) {
            if (waveform.amplitudes[i] > 0) on += waveform.timings[i]
        }
        val duty = on.toDouble() / waveform.totalMs
        assertThat(duty).isWithin(0.08).of(128 / 255.0)
    }

    @Test
    fun `pwm high amplitude renders solid`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(1000, WaveformMapper.SOLID_AMPLITUDE_THRESHOLD)),
            hasAmplitudeControl = false,
        )!!
        assertThat(waveform.timings.toList()).containsExactly(1000L)
        assertThat(waveform.amplitudes.toList()).containsExactly(255)
    }

    @Test
    fun `pwm respects minimum pulse width for tiny amplitudes`() {
        val waveform = WaveformMapper.toWaveform(
            listOf(vibrate(800, 5)),
            hasAmplitudeControl = false,
        )!!
        for (i in waveform.timings.indices) {
            if (waveform.amplitudes[i] > 0) {
                assertThat(waveform.timings[i]).isAtLeast(WaveformMapper.MIN_PWM_PULSE_MS)
            }
        }
    }

    // --- Property-style checks ---

    @Test
    fun `total duration equals timing sum for random patterns on both paths`() {
        val random = Random(42)
        repeat(200) {
            val segments = List(random.nextInt(1, 12)) {
                if (random.nextBoolean()) {
                    vibrate(random.nextLong(1, 3000), random.nextInt(1, 256))
                } else {
                    pause(random.nextLong(1, 2000))
                }
            }
            for (hasAmplitude in listOf(true, false)) {
                val waveform = WaveformMapper.toWaveform(
                    segments,
                    hasAmplitudeControl = hasAmplitude,
                ) ?: continue
                assertThat(waveform.timings.sum()).isEqualTo(waveform.totalMs)
                assertThat(waveform.timings.size).isEqualTo(waveform.amplitudes.size)
                // No zero-length entries survive.
                assertThat(waveform.timings.all { t -> t > 0 }).isTrue()
            }
        }
    }
}

/** The rule behind the intensity slider's preview burst. */
class PeakAmplitudeTest {

    @Test
    fun `the peak is the loudest vibrate step`() {
        val pattern = listOf(vibrate(400, 60), pause(900), vibrate(200, 180), pause(300))
        assertThat(pattern.peakAmplitude).isEqualTo(180)
    }

    @Test
    fun `pauses do not count as strength`() {
        assertThat(listOf(vibrate(100, 40), pause(9_000)).peakAmplitude).isEqualTo(40)
    }

    /**
     * A gentle pattern must preview gently even at full intensity — the slider scales the
     * pattern the user built rather than replacing it with a maximum-strength buzz.
     */
    @Test
    fun `a gentle pattern peaks gently`() {
        assertThat(listOf(vibrate(400, 60)).peakAmplitude).isLessThan(PatternSegment.MAX_AMPLITUDE)
    }

    @Test
    fun `a pattern with no vibration still yields feedback at full strength`() {
        assertThat(emptyList<PatternSegment>().peakAmplitude).isEqualTo(PatternSegment.MAX_AMPLITUDE)
        assertThat(listOf(pause(500)).peakAmplitude).isEqualTo(PatternSegment.MAX_AMPLITUDE)
    }
}
