package com.faybish.vibealarm.domain

import java.time.Duration

/**
 * A ringtone that climbs from quiet to the chosen volume instead of opening at full level.
 *
 * Pure so the curve can be reasoned about without a speaker: the engine only steps a
 * MediaPlayer's volume to whatever [fractionAt] returns.
 */
object VolumeRamp {

    /**
     * Where the ramp starts, as a fraction of the volume the user chose — not of the
     * device maximum. Starting from silence would make the first seconds indistinguishable
     * from a broken alarm.
     */
    const val START_FRACTION = 0.1f

    val DEFAULT_DURATION: Duration = Duration.ofSeconds(30)

    private const val MIN_RAMP_MS = 1_000L

    /**
     * How long the climb may take inside a ring window of [windowMs].
     *
     * Half the window, capped at [DEFAULT_DURATION]: a 20-second ringtone that only
     * reached full volume as it stopped would be a gentler alarm than anybody asked for,
     * so the second half of any window always plays at the chosen level.
     */
    fun rampMillis(windowMs: Long, requested: Duration = DEFAULT_DURATION): Long =
        minOf(requested.toMillis(), windowMs / 2).coerceAtLeast(MIN_RAMP_MS)

    /**
     * Fraction of the chosen volume at [elapsedMs] into a ramp of [rampMillis].
     *
     * Squared rather than linear in time, because loudness is perceived roughly
     * logarithmically: a linear amplitude ramp is heard as a jump at the start and
     * nothing afterwards, which defeats the point of starting quietly.
     */
    fun fractionAt(elapsedMs: Long, rampMillis: Long): Float {
        if (rampMillis <= 0L) return 1f
        val progress = (elapsedMs.toFloat() / rampMillis).coerceIn(0f, 1f)
        return (START_FRACTION + (1f - START_FRACTION) * progress * progress).coerceIn(0f, 1f)
    }
}
