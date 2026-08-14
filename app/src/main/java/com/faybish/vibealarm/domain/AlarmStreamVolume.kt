package com.faybish.vibealarm.domain

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How to reach the per-alarm volume without turning anybody else's alarm down.
 *
 * A MediaPlayer's volume is relative to its stream, so per-alarm volume needs the alarm
 * stream to be at least as loud as the level we want. The blunt version of that — set the
 * stream to exactly our level — also **lowers** it, and the alarm stream is shared: a 30%
 * alarm of ours would play the built-in clock's alarm at 30% too, for as long as ours runs.
 * That is indistinguishable from "this app broke my other alarm clock".
 *
 * So the stream is only ever raised, and the rest of the distance is covered by attenuating
 * our own player.
 */
object AlarmStreamVolume {

    data class Plan(
        /** Non-null only when the stream is too quiet for the level asked for. */
        val raiseStreamTo: Int?,
        /** What the player itself should use, relative to the stream it ends up on. */
        val playerVolume: Float,
    )

    fun plan(requested: Float, currentIndex: Int, maxIndex: Int): Plan {
        if (maxIndex <= 0) return Plan(raiseStreamTo = null, playerVolume = 1f)

        val wanted = wantedIndex(requested, maxIndex)

        return if (currentIndex >= wanted) {
            // Loud enough already — leave the shared stream alone and turn ourselves down.
            Plan(raiseStreamTo = null, playerVolume = wanted.toFloat() / currentIndex)
        } else {
            Plan(raiseStreamTo = wanted, playerVolume = 1f)
        }
    }

    /**
     * The stream index that means what the user chose. Never zero, even at the bottom of the
     * slider: an alarm the user asked to hear has to make a sound.
     */
    fun wantedIndex(requested: Float, maxIndex: Int): Int =
        (requested.coerceIn(0f, 1f) * maxIndex).roundToInt().coerceIn(1, maxIndex)

    /**
     * How much to turn the player down so that a louder stream sounds like the level the user
     * asked for.
     *
     * Volume indices are spaced in **decibels** while `MediaPlayer.setVolume` is a linear
     * amplitude scale, so dividing one index by another is only roughly right — a couple of
     * dB out, which is audible. Given the real dB values of the two indices the exact
     * attenuation is available, and the alarm then plays at the chosen loudness rather than
     * near it.
     *
     * @return 1.0 when the stream is already at (or below) the wanted level, so this can
     *   never make an alarm louder than the phone is set to allow.
     */
    fun attenuationForDb(wantedDb: Float, currentDb: Float): Float {
        if (!wantedDb.isFinite() || !currentDb.isFinite()) return 1f
        if (wantedDb >= currentDb) return 1f
        return 10f.pow((wantedDb - currentDb) / 20f).coerceIn(0f, 1f)
    }
}
