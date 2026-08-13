package com.faybish.vibealarm.domain

/**
 * How long a ringtone plays before the chain auto-snoozes.
 *
 * The presets cover the usual answers; the custom value exists because "how long should it
 * ring" is a question about a specific bedroom, and minutes are a coarse unit for it.
 */
object AutoSilence {

    val PRESET_SECONDS: List<Int> = listOf(60, 120, 300, 600)

    /** Below this the ringtone would barely start; above it, half an hour is plenty. */
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 30 * 60

    fun isCustom(seconds: Int): Boolean = seconds !in PRESET_SECONDS

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** @return null when [text] is not a number of seconds this app will accept. */
    fun parse(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in MIN_SECONDS..MAX_SECONDS }
}
