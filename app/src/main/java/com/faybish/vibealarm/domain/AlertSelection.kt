package com.faybish.vibealarm.domain

/**
 * What an alarm does when it goes off: make a sound, vibrate, or both.
 *
 * Expressed as two independent switches because that is how people think about it. The
 * older shape — a "vibration only / sound" choice plus a separate "vibrate as well" flag
 * — encoded the same three states, but nobody could tell what the flag meant next to the
 * choice above it.
 *
 * The one rule: at least one of them is always on. An alarm that neither sounds nor
 * vibrates is not an alarm, and it is the kind of setting that looks harmless in the
 * evening and is discovered the following morning.
 */
data class AlertSelection(val sound: Boolean, val vibration: Boolean) {

    init {
        require(sound || vibration) { "an alarm must either sound or vibrate" }
    }

    /**
     * Applies a toggle, refusing the one change that would silence the alarm entirely.
     * Returns the same selection in that case, so the switch simply does not move.
     */
    fun toggleSound(): AlertSelection =
        if (sound && !vibration) this else copy(sound = !sound)

    fun toggleVibration(): AlertSelection =
        if (vibration && !sound) this else copy(vibration = !vibration)

    /** True when turning this one off would leave nothing — the UI explains why. */
    val soundIsLastOne: Boolean get() = sound && !vibration
    val vibrationIsLastOne: Boolean get() = vibration && !sound

    companion object {
        val VIBRATION_ONLY = AlertSelection(sound = false, vibration = true)

        /**
         * Rebuilds the selection from the stored columns.
         *
         * The storage keeps its original shape on purpose: the two columns already encode
         * exactly these three states, and migrating the schema of an installed alarm clock
         * to make a screen read better is not a trade worth making.
         *
         * @param soundMode true when the alarm's mode column says SOUND.
         */
        fun fromStorage(soundMode: Boolean, vibrateWithSound: Boolean): AlertSelection =
            if (soundMode) {
                AlertSelection(sound = true, vibration = vibrateWithSound)
            } else {
                VIBRATION_ONLY
            }
    }

    /** The mode column: sound wins, because vibration rides along with it. */
    val storedAsSoundMode: Boolean get() = sound

    /**
     * The vibrate-with-sound column. Meaningless when the mode is vibration-only, and
     * written as true there so a later switch to sound keeps the vibration the user had.
     */
    val storedVibrateWithSound: Boolean get() = if (sound) vibration else true
}
