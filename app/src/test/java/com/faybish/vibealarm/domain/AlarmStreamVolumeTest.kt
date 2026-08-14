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

    @Test
    fun `a device reporting no volume steps is left alone`() {
        val plan = AlarmStreamVolume.plan(requested = 0.5f, currentIndex = 0, maxIndex = 0)

        assertThat(plan.raiseStreamTo).isNull()
        assertThat(plan.playerVolume).isEqualTo(1f)
    }
}
