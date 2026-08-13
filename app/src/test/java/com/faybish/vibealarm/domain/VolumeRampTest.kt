package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

class VolumeRampTest {

    @Test
    fun `the ramp starts audible and ends at the chosen volume`() {
        assertThat(VolumeRamp.fractionAt(0, 30_000)).isEqualTo(VolumeRamp.START_FRACTION)
        assertThat(VolumeRamp.fractionAt(30_000, 30_000)).isEqualTo(1f)
    }

    @Test
    fun `it never overshoots or goes backwards`() {
        var previous = 0f
        (0..40).forEach { second ->
            val level = VolumeRamp.fractionAt(second * 1000L, 30_000)
            assertThat(level).isAtLeast(previous)
            assertThat(level).isAtMost(1f)
            previous = level
        }
        assertThat(VolumeRamp.fractionAt(60_000, 30_000)).isEqualTo(1f)
    }

    /**
     * Squared, so the quiet part actually lasts. A linear ramp is at 60% of the target
     * halfway through, which is heard as "it just started loud".
     */
    @Test
    fun `the climb is weighted towards the end`() {
        assertThat(VolumeRamp.fractionAt(15_000, 30_000)).isLessThan(0.4f)
    }

    @Test
    fun `no ramp means full volume immediately`() {
        assertThat(VolumeRamp.fractionAt(0, 0)).isEqualTo(1f)
    }

    /** A 20-second ringtone that only reached full volume as it stopped is not an alarm. */
    @Test
    fun `the ramp takes at most half the ring window`() {
        assertThat(VolumeRamp.rampMillis(windowMs = 20_000)).isEqualTo(10_000)
        assertThat(VolumeRamp.rampMillis(windowMs = 300_000)).isEqualTo(30_000)
    }

    @Test
    fun `a very short window still ramps for a moment rather than not at all`() {
        assertThat(VolumeRamp.rampMillis(windowMs = 1_000)).isEqualTo(1_000)
        assertThat(VolumeRamp.rampMillis(windowMs = 0)).isEqualTo(1_000)
    }

    @Test
    fun `a shorter requested ramp is honoured`() {
        assertThat(VolumeRamp.rampMillis(300_000, Duration.ofSeconds(5))).isEqualTo(5_000)
    }
}
