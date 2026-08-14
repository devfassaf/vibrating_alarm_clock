package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rule this pins: the ring duration governs the ringtone, the pattern governs the
 * vibration, and neither cuts the other short.
 */
class AlertWindowTest {

    private val thirtySecondPattern = 30_000L
    private val tenSecondPattern = 10_000L

    /** The reported bug: a 30-second pattern silenced after 10 seconds of ringtone. */
    @Test
    fun `a pattern longer than the ringtone plays to its end`() {
        val plan = AlertWindow.plan(
            patternMs = thirtySecondPattern,
            autoSilenceSeconds = 10,
            sound = true,
            vibration = true,
        )

        assertThat(plan.vibrationMs).isEqualTo(30_000)
        assertThat(plan.soundMs).isEqualTo(10_000)
        // The window waits for the pattern…
        assertThat(plan.windowMs).isEqualTo(30_000)
        // …and the ringtone is silenced on its own schedule.
        assertThat(plan.stopSoundBeforeWindowEnds).isTrue()
    }

    /** The other direction: the pattern plays once and the ringtone keeps going. */
    @Test
    fun `a ringtone longer than the pattern is not cut short either`() {
        val plan = AlertWindow.plan(
            patternMs = tenSecondPattern,
            autoSilenceSeconds = 300,
            sound = true,
            vibration = true,
        )

        assertThat(plan.windowMs).isEqualTo(300_000)
        // Nothing to stop early — the ringtone is what ends the window.
        assertThat(plan.stopSoundBeforeWindowEnds).isFalse()
    }

    @Test
    fun `vibration only lasts exactly one pass of the pattern`() {
        val plan = AlertWindow.plan(
            patternMs = thirtySecondPattern,
            autoSilenceSeconds = 600,
            sound = false,
            vibration = true,
        )

        assertThat(plan.soundMs).isEqualTo(0)
        assertThat(plan.windowMs).isEqualTo(30_000)
        assertThat(plan.stopSoundBeforeWindowEnds).isFalse()
    }

    @Test
    fun `sound only lasts exactly the chosen duration`() {
        val plan = AlertWindow.plan(
            patternMs = thirtySecondPattern,
            autoSilenceSeconds = 45,
            sound = true,
            vibration = false,
        )

        assertThat(plan.vibrationMs).isEqualTo(0)
        assertThat(plan.windowMs).isEqualTo(45_000)
    }

    /** A device with no vibrator reports nothing; the chain must not become a fast loop. */
    @Test
    fun `a zero-length window is floored`() {
        val plan = AlertWindow.plan(patternMs = 0, autoSilenceSeconds = 60, sound = false, vibration = true)

        assertThat(plan.windowMs).isEqualTo(AlertWindow.MIN_WINDOW_MS)
    }

    @Test
    fun `a ring duration of zero is still a second of sound`() {
        val plan = AlertWindow.plan(patternMs = 0, autoSilenceSeconds = 0, sound = true, vibration = false)

        assertThat(plan.soundMs).isEqualTo(1_000)
    }

    /** Custom durations in seconds are the point of the field that produces them. */
    @Test
    fun `an odd number of seconds is honoured exactly`() {
        val plan = AlertWindow.plan(patternMs = 4_000, autoSilenceSeconds = 45, sound = true, vibration = true)

        assertThat(plan.soundMs).isEqualTo(45_000)
        assertThat(plan.windowMs).isEqualTo(45_000)
        assertThat(plan.stopSoundBeforeWindowEnds).isFalse()
    }
}
