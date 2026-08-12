package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecorderQuantizerTest {

    @Test
    fun `empty input yields empty pattern`() {
        assertThat(RecorderQuantizer.quantize(emptyList())).isEmpty()
    }

    @Test
    fun `single press becomes one vibrate segment with no leading pause`() {
        val segments = RecorderQuantizer.quantize(
            listOf(RecordedPress(startMs = 500, endMs = 800, amplitude = 180)),
        )
        assertThat(segments).containsExactly(PatternSegment.vibrate(300, 180))
    }

    @Test
    fun `gap between presses becomes a pause`() {
        val segments = RecorderQuantizer.quantize(
            listOf(
                RecordedPress(0, 100, 100),
                RecordedPress(400, 500, 200),
            ),
        )
        assertThat(segments).containsExactly(
            PatternSegment.vibrate(100, 100),
            PatternSegment.pause(300),
            PatternSegment.vibrate(100, 200),
        ).inOrder()
    }

    @Test
    fun `micro-gaps merge presses with weighted amplitude`() {
        val segments = RecorderQuantizer.quantize(
            listOf(
                RecordedPress(0, 100, 100), // 100ms @ 100
                RecordedPress(120, 300, 200), // 20ms gap < 30 -> merge; 180ms @ 200
            ),
        )
        // Weighted mean: (100*100 + 200*180) / 280 = 164
        assertThat(segments).containsExactly(PatternSegment.vibrate(300, 164))
    }

    @Test
    fun `short presses are floored to the minimum segment duration`() {
        val segments = RecorderQuantizer.quantize(
            listOf(RecordedPress(0, 20, 255)),
        )
        assertThat(segments).containsExactly(
            PatternSegment.vibrate(RecorderQuantizer.MIN_SEGMENT_MS, 255),
        )
    }

    @Test
    fun `zero-duration presses are dropped`() {
        val segments = RecorderQuantizer.quantize(
            listOf(
                RecordedPress(0, 0, 255),
                RecordedPress(100, 300, 128),
            ),
        )
        assertThat(segments).containsExactly(PatternSegment.vibrate(200, 128))
    }

    @Test
    fun `out-of-order presses are sorted before processing`() {
        val segments = RecorderQuantizer.quantize(
            listOf(
                RecordedPress(400, 500, 200),
                RecordedPress(0, 100, 100),
            ),
        )
        assertThat(segments.first()).isEqualTo(PatternSegment.vibrate(100, 100))
        assertThat(segments.last()).isEqualTo(PatternSegment.vibrate(100, 200))
    }

    @Test
    fun `amplitudes are clamped into valid range`() {
        val segments = RecorderQuantizer.quantize(
            listOf(RecordedPress(0, 100, 999)),
        )
        assertThat((segments.single()).amplitude).isEqualTo(PatternSegment.MAX_AMPLITUDE)
    }
}
