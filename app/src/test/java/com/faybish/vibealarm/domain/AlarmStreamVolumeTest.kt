package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The alarm stream is shared with every other alarm clock on the phone, so this app may
 * raise it and must never lower it.
 */
class AlarmStreamVolumeTest {

    private val max = 10

    /** The case that looked like "VibeAlarm broke my built-in alarm". */
    @Test
    fun `a quiet alarm does not turn the shared stream down`() {
        val plan = AlarmStreamVolume.plan(requested = 0.3f, currentIndex = max, maxIndex = max)

        assertThat(plan.raiseStreamTo).isNull()
        assertThat(plan.playerVolume).isEqualTo(0.3f)
    }

    @Test
    fun `a stream too quiet for the alarm is raised`() {
        val plan = AlarmStreamVolume.plan(requested = 1f, currentIndex = 2, maxIndex = max)

        assertThat(plan.raiseStreamTo).isEqualTo(max)
        assertThat(plan.playerVolume).isEqualTo(1f)
    }

    @Test
    fun `an exactly matching stream is left alone at full player volume`() {
        val plan = AlarmStreamVolume.plan(requested = 0.5f, currentIndex = 5, maxIndex = max)

        assertThat(plan.raiseStreamTo).isNull()
        assertThat(plan.playerVolume).isEqualTo(1f)
    }

    /** The bottom of the slider is quiet, not silent. */
    @Test
    fun `zero still asks for one step of volume`() {
        assertThat(AlarmStreamVolume.plan(0f, currentIndex = 0, maxIndex = max).raiseStreamTo)
            .isEqualTo(1)
    }

    @Test
    fun `a muted stream is raised to the level the alarm needs`() {
        val plan = AlarmStreamVolume.plan(requested = 0.8f, currentIndex = 0, maxIndex = max)

        assertThat(plan.raiseStreamTo).isEqualTo(8)
        assertThat(plan.playerVolume).isEqualTo(1f)
    }

    /** The absolute loudness has to come out the same either way. */
    @Test
    fun `the resulting loudness matches what was asked for`() {
        listOf(0.2f, 0.5f, 0.8f, 1f).forEach { requested ->
            (0..max).forEach { current ->
                val plan = AlarmStreamVolume.plan(requested, current, max)
                val streamAfter = plan.raiseStreamTo ?: current
                val effective = (streamAfter / max.toFloat()) * plan.playerVolume
                val wanted = (requested * max).toInt().coerceAtLeast(1) / max.toFloat()
                assertThat(effective).isWithin(0.06f).of(wanted)
            }
        }
    }

    // --- the exact attenuation ---

    /** Volume indices are dB-spaced, so the correction has to be too. */
    @Test
    fun `six decibels down is half the amplitude`() {
        assertThat(AlarmStreamVolume.attenuationForDb(wantedDb = -6f, currentDb = 0f))
            .isWithin(0.01f).of(0.5f)
        assertThat(AlarmStreamVolume.attenuationForDb(wantedDb = -12f, currentDb = 0f))
            .isWithin(0.01f).of(0.25f)
        assertThat(AlarmStreamVolume.attenuationForDb(wantedDb = -26f, currentDb = -20f))
            .isWithin(0.01f).of(0.5f)
    }

    /** This may only ever turn the alarm down; the stream is the ceiling the phone sets. */
    @Test
    fun `it never makes the alarm louder than the stream allows`() {
        assertThat(AlarmStreamVolume.attenuationForDb(wantedDb = 0f, currentDb = -20f))
            .isEqualTo(1f)
        assertThat(AlarmStreamVolume.attenuationForDb(wantedDb = -3f, currentDb = -3f))
            .isEqualTo(1f)
    }

    /** Some devices report silence as -infinity for index 0. */
    @Test
    fun `a device answering with infinity is ignored rather than trusted`() {
        assertThat(AlarmStreamVolume.attenuationForDb(Float.NEGATIVE_INFINITY, 0f)).isEqualTo(1f)
        assertThat(AlarmStreamVolume.attenuationForDb(-6f, Float.NEGATIVE_INFINITY)).isEqualTo(1f)
        assertThat(AlarmStreamVolume.attenuationForDb(Float.NaN, 0f)).isEqualTo(1f)
    }

    @Test
    fun `the wanted index is the chosen fraction of the range, never zero`() {
        assertThat(AlarmStreamVolume.wantedIndex(1f, 7)).isEqualTo(7)
        assertThat(AlarmStreamVolume.wantedIndex(0.5f, 10)).isEqualTo(5)
        assertThat(AlarmStreamVolume.wantedIndex(0f, 7)).isEqualTo(1)
        assertThat(AlarmStreamVolume.wantedIndex(0.01f, 7)).isEqualTo(1)
    }

    @Test
    fun `a device reporting no volume steps is left alone`() {
        val plan = AlarmStreamVolume.plan(requested = 0.5f, currentIndex = 0, maxIndex = 0)

        assertThat(plan.raiseStreamTo).isNull()
        assertThat(plan.playerVolume).isEqualTo(1f)
    }
}
