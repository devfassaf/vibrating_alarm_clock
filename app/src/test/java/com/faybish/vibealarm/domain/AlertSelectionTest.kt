package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AlertSelectionTest {

    // --- the invariant ---

    /**
     * An alarm that neither sounds nor vibrates is not an alarm. The state is
     * unrepresentable rather than merely discouraged, so no screen can produce it.
     */
    @Test
    fun `an alarm that does nothing cannot be constructed`() {
        runCatching { AlertSelection(sound = false, vibration = false) }
            .let { assertThat(it.isFailure).isTrue() }
    }

    @Test
    fun `turning off the last one is refused, so the switch does not move`() {
        val soundOnly = AlertSelection(sound = true, vibration = false)
        assertThat(soundOnly.toggleSound()).isEqualTo(soundOnly)
        assertThat(soundOnly.soundIsLastOne).isTrue()

        val vibrationOnly = AlertSelection.VIBRATION_ONLY
        assertThat(vibrationOnly.toggleVibration()).isEqualTo(vibrationOnly)
        assertThat(vibrationOnly.vibrationIsLastOne).isTrue()
    }

    @Test
    fun `with both on, either can be turned off`() {
        val both = AlertSelection(sound = true, vibration = true)
        assertThat(both.toggleSound()).isEqualTo(AlertSelection.VIBRATION_ONLY)
        assertThat(both.toggleVibration()).isEqualTo(AlertSelection(sound = true, vibration = false))
        assertThat(both.soundIsLastOne).isFalse()
        assertThat(both.vibrationIsLastOne).isFalse()
    }

    @Test
    fun `turning the other one on always works`() {
        assertThat(AlertSelection.VIBRATION_ONLY.toggleSound())
            .isEqualTo(AlertSelection(sound = true, vibration = true))
        assertThat(AlertSelection(sound = true, vibration = false).toggleVibration())
            .isEqualTo(AlertSelection(sound = true, vibration = true))
    }

    // --- storage mapping ---

    /**
     * The screen changed shape; the columns did not. Every one of the three states must
     * survive a round trip, or a shipped alarm would come back meaning something else.
     */
    @Test
    fun `all three states round-trip through the stored columns`() {
        listOf(
            AlertSelection.VIBRATION_ONLY,
            AlertSelection(sound = true, vibration = false),
            AlertSelection(sound = true, vibration = true),
        ).forEach { selection ->
            val restored = AlertSelection.fromStorage(
                soundMode = selection.storedAsSoundMode,
                vibrateWithSound = selection.storedVibrateWithSound,
            )
            assertThat(restored).isEqualTo(selection)
        }
    }

    @Test
    fun `an existing vibration-only alarm reads as vibration only whatever the other column says`() {
        // The vibrate-with-sound column is meaningless in that mode, and alarms saved by
        // earlier versions carry either value in it.
        listOf(true, false).forEach { flag ->
            assertThat(AlertSelection.fromStorage(soundMode = false, vibrateWithSound = flag))
                .isEqualTo(AlertSelection.VIBRATION_ONLY)
        }
    }

    @Test
    fun `an existing sound alarm keeps whether it also vibrated`() {
        assertThat(AlertSelection.fromStorage(soundMode = true, vibrateWithSound = true))
            .isEqualTo(AlertSelection(sound = true, vibration = true))
        assertThat(AlertSelection.fromStorage(soundMode = true, vibrateWithSound = false))
            .isEqualTo(AlertSelection(sound = true, vibration = false))
    }

    /**
     * Switching to vibration-only and back must not silently drop the vibration the user
     * had chosen alongside the sound.
     */
    @Test
    fun `dropping to vibration only and back restores both`() {
        val both = AlertSelection(sound = true, vibration = true)
        val vibrationOnly = both.toggleSound()
        val stored = AlertSelection.fromStorage(
            soundMode = vibrationOnly.storedAsSoundMode,
            vibrateWithSound = vibrationOnly.storedVibrateWithSound,
        )
        assertThat(stored.toggleSound()).isEqualTo(both)
    }
}
