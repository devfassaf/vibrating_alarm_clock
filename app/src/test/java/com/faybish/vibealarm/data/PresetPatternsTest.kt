package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.SegmentType
import com.faybish.vibealarm.domain.WaveformMapper
import com.faybish.vibealarm.domain.totalDurationMs
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The patterns shipped with the app are the alarm for anyone who never opens the builder, and
 * they are stored as JSON — so a typo in one of them is a silent morning that no compiler
 * would have caught.
 */
class PresetPatternsTest {

    @Test
    fun `every preset decodes to something that actually vibrates`() {
        assertThat(PresetPatterns.all).isNotEmpty()

        PresetPatterns.all.forEach { pattern ->
            val segments = SegmentsCodec.decode(pattern.segmentsJson)
            assertWithMessage("${pattern.name} decodes").that(segments).isNotEmpty()
            assertWithMessage("${pattern.name} has vibration")
                .that(segments.any { it.type == SegmentType.VIBRATE })
                .isTrue()
        }
    }

    @Test
    fun `every preset is within the bounds the engine accepts`() {
        PresetPatterns.all.forEach { pattern ->
            SegmentsCodec.decode(pattern.segmentsJson).forEachIndexed { index, segment ->
                val where = "${pattern.name} segment $index"
                assertWithMessage("$where duration").that(segment.durationMs).isGreaterThan(0L)
                if (segment.type == SegmentType.VIBRATE) {
                    assertWithMessage("$where amplitude")
                        .that(segment.amplitude)
                        .isIn(1..PatternSegment.MAX_AMPLITUDE)
                }
            }
        }
    }

    /**
     * Long enough to wake someone, short enough to be one pass rather than a minute of
     * buzzing — the whole premise of the app is that a ring ends by itself.
     */
    @Test
    fun `every preset lasts a sensible single pass`() {
        PresetPatterns.all.forEach { pattern ->
            val total = SegmentsCodec.decode(pattern.segmentsJson).totalDurationMs
            assertWithMessage("${pattern.name} total ms = $total")
                .that(total in 2_000..60_000)
                .isTrue()
        }
    }

    @Test
    fun `every preset produces a playable waveform`() {
        PresetPatterns.all.forEach { pattern ->
            val segments = SegmentsCodec.decode(pattern.segmentsJson)
            listOf(true, false).forEach { amplitudeControl ->
                val waveform = WaveformMapper.toWaveform(
                    segments = segments,
                    intensityScale = 1f,
                    hasAmplitudeControl = amplitudeControl,
                )
                assertWithMessage("${pattern.name} amplitudeControl=$amplitudeControl")
                    .that(waveform)
                    .isNotNull()
                assertThat(waveform!!.timings.size).isEqualTo(waveform.amplitudes.size)
                assertThat(waveform.totalMs).isGreaterThan(0L)
            }
        }
    }

    @Test
    fun `presets are marked as presets and named distinctly`() {
        assertThat(PresetPatterns.all.map { it.name }).containsNoDuplicates()
        PresetPatterns.all.forEach {
            assertWithMessage(it.name).that(it.isPreset).isTrue()
            assertWithMessage("${it.name} name").that(it.name).isNotEmpty()
        }
    }

    /** The fallback used when an alarm points at no pattern at all. */
    @Test
    fun `the default segments are never empty`() {
        assertThat(PresetPatterns.DEFAULT_SEGMENTS).isNotEmpty()
        assertThat(PresetPatterns.DEFAULT_SEGMENTS.any { it.type == SegmentType.VIBRATE }).isTrue()
        assertThat(PresetPatterns.DEFAULT_SEGMENTS.totalDurationMs).isGreaterThan(1_000L)
    }
}
