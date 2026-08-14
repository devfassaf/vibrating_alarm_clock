package com.faybish.vibealarm.domain

/**
 * The snooze numbers the card offers, and the bounds on anything typed instead.
 *
 * The presets are the usual answers; the free field exists because "how many times should it
 * come back" and "how far apart" are questions about one person's morning, and four buttons
 * cannot be the whole answer.
 */
object SnoozeRepeats {

    /** -1 is "until dismissed" and 0 is "none"; both are choices, not counts. */
    const val UNTIL_DISMISSED = -1
    const val NONE = 0

    val PRESETS: List<Int> = listOf(NONE, 1, 3, 5, UNTIL_DISMISSED)

    const val MIN = 1
    const val MAX = 99

    fun isCustom(count: Int): Boolean = count !in PRESETS

    fun clamp(count: Int): Int = count.coerceIn(MIN, MAX)

    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }
}

/** The gap between one ring and the next, in minutes. */
object SnoozeInterval {

    val PRESETS: List<Int> = listOf(1, 3, 5, 10)

    const val MIN = 1

    /** Two hours. Beyond that it is not a snooze, it is a second alarm. */
    const val MAX = 120

    fun isCustom(minutes: Int): Boolean = minutes !in PRESETS

    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN, MAX)

    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }
}
