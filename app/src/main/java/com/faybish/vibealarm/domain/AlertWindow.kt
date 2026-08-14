package com.faybish.vibealarm.domain

/**
 * How long one ring lasts, when sound and vibration disagree about it.
 *
 * They are two separate answers to two separate questions: **the ringtone plays for as long
 * as the user asked**, and **the pattern plays once, whatever that takes**. Before this, the
 * ring window was the ringtone's length alone, so a 30-second pattern with a 10-second
 * ringtone was cut off two thirds of the way through — the pattern the user built, silenced
 * by a setting about sound.
 *
 * The alerting window is therefore the longer of the two, and whichever finishes first stops
 * on its own while the other keeps going.
 */
object AlertWindow {

    /**
     * Guards against a device with no vibrator reporting a zero-length pattern and turning
     * the chain into an instant snooze loop.
     */
    const val MIN_WINDOW_MS = 1_000L

    data class Plan(
        /** 0 when this alarm does not sound. */
        val soundMs: Long,
        /** 0 when this alarm does not vibrate; otherwise one pass of the pattern. */
        val vibrationMs: Long,
    ) {
        /** When the transition to snooze/end happens: after both have had their say. */
        val windowMs: Long get() = maxOf(soundMs, vibrationMs).coerceAtLeast(MIN_WINDOW_MS)

        /**
         * True when the ringtone has to be silenced before the window ends — a long pattern
         * next to a short ringtone. The vibration needs no such timer: a waveform played
         * once stops itself.
         */
        val stopSoundBeforeWindowEnds: Boolean get() = soundMs in 1 until windowMs
    }

    /**
     * @param patternMs one pass of the vibration pattern, as the engine reports it.
     * @param autoSilenceSeconds the user's ring duration; at least one second is honoured.
     */
    fun plan(
        patternMs: Long,
        autoSilenceSeconds: Int,
        sound: Boolean,
        vibration: Boolean,
    ): Plan = Plan(
        soundMs = if (sound) autoSilenceSeconds.coerceAtLeast(1) * 1000L else 0L,
        vibrationMs = if (vibration) patternMs.coerceAtLeast(0L) else 0L,
    )
}
