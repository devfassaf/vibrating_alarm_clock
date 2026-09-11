package com.faybish.vibealarm.domain

import kotlin.math.log10
import kotlin.math.pow

/**
 * How loud the alarm actually comes out, and how little of the shared stream has to move
 * to get there.
 *
 * Two knobs reach a ringtone, and only one of them is ours. The **alarm stream** is shared
 * with every other alarm clock on the phone, its steps are spaced in decibels, and there
 * are only a handful of them (7 on a Pixel, 15 on some phones). The **player's own volume**
 * is a linear amplitude gain nobody else can hear. So the chosen percentage is delivered by
 * the player, and the stream is moved only far enough — and only **up** — to give the player
 * the headroom it needs.
 *
 * What the slider means here is *how far below the loudest alarm this phone will play* —
 * [RANGE_DB] at the bottom, nothing at the top, spread evenly in between. That is a fixed
 * loudness on any device and at any system volume. It deliberately does **not** mean "step
 * that far up the phone's own volume slider": that was the old behaviour and it had two
 * faults, both of which read as "the volume setting does nothing".
 *
 *  - It snapped the slider to the stream's steps. With 7 steps, every value from 65% to 78%
 *    produced the exact same sound, and 93%–100% likewise.
 *  - It asked the device how loud each step is ([Curve]) and believed the answer. A phone
 *    that reports the same figure for every step — and they exist — got no attenuation at
 *    all, so 10% and 100% came out identically loud whenever the phone's own alarm volume
 *    was already up, which for an alarm clock is most of the time.
 *
 * The curve is now checked before it is trusted, and a device that cannot describe its own
 * steps simply loses the right to influence the result: the stream goes to its maximum,
 * whose loudness needs no device to explain it, and the player carries the whole distance.
 */
object AlarmStreamVolume {

    /**
     * How much quieter the bottom of the slider is than the top, in dB — and the slider
     * travels that range **evenly**, 3 dB per 10%.
     *
     * Loudness is heard roughly logarithmically, so a slider that is linear in amplitude
     * spends its top third doing almost nothing audible: 100% to 80% is under 2 dB. That is
     * a slider a user moves and hears no difference from, which is the complaint this whole
     * file exists to answer. A phone's own volume slider is spaced in dB for the same
     * reason, and this one is now spaced like it.
     *
     * 30 dB is where the bottom of the slider lands, which is about the quietest step a
     * phone offers for its own alarms: quiet, never silent. An alarm the user asked to hear
     * has to make a sound.
     */
    const val RANGE_DB = 30f

    /**
     * How much loudness a device's own answers have to span before they are believed. A
     * phone whose quietest and loudest alarm steps are within 6 dB of each other is not
     * describing a volume curve, it is declining to answer.
     */
    private const val MIN_CURVE_SPREAD_DB = 6f

    data class Plan(
        /** Non-null only when the stream has to come up; it is never asked to go down. */
        val raiseStreamTo: Int?,
        /** What the player itself should use, relative to the stream it ends up on. */
        val playerVolume: Float,
    )

    /**
     * The device's own loudness, in dB, for each alarm-stream index — checked, so that
     * only a device that really answered gets a say.
     *
     * [of] returns null for a curve that is not one: non-finite figures (some devices
     * report negative infinity for a silent step), a curve that does not rise with the
     * index, or one whose whole range is less than [MIN_CURVE_SPREAD_DB].
     */
    class Curve private constructor(private val db: FloatArray) {

        val maxIndex: Int get() = db.size

        /** The loudness of [index], clamped into the range the device actually has. */
        fun dbAt(index: Int): Float = db[index.coerceIn(1, maxIndex) - 1]

        /** The quietest step that is still at least as loud as [db] — never below 1. */
        fun lowestIndexAtLeast(db: Float): Int =
            (1..maxIndex).firstOrNull { dbAt(it) >= db } ?: maxIndex

        companion object {
            /**
             * @param dbAt the platform's `AudioManager.getStreamVolumeDb` for one index,
             *   or null where it is unavailable (below API 28) or refused.
             */
            fun of(maxIndex: Int, dbAt: (Int) -> Float?): Curve? {
                if (maxIndex <= 0) return null
                val values = FloatArray(maxIndex)
                for (index in 1..maxIndex) {
                    val value = dbAt(index) ?: return null
                    if (!value.isFinite()) return null
                    if (index > 1 && value < values[index - 2]) return null
                    values[index - 1] = value
                }
                if (values.last() - values.first() < MIN_CURVE_SPREAD_DB) return null
                return Curve(values)
            }
        }
    }

    /**
     * How far below the loudest alarm this phone can play the chosen volume sits, in dB.
     * This is the whole promise of the setting: hit this figure and the alarm is as loud
     * as the user asked for, whatever the phone's own volume happens to be.
     */
    fun requestedDb(requested: Float): Float = (requested.coerceIn(0f, 1f) - 1f) * RANGE_DB

    /**
     * @param currentIndex the alarm stream's index now; pass 0 for a muted stream, which
     *   ignores the level it is set to until something unmutes it.
     * @param curve the device's checked volume curve, or null to stop trusting indices.
     * @param mayRaise false when the stream cannot be moved at all — a device that refuses
     *   the write. The player then covers what it can and the phone's own volume becomes
     *   the ceiling, which is the most an app can do.
     */
    fun plan(
        requested: Float,
        currentIndex: Int,
        maxIndex: Int,
        curve: Curve?,
        mayRaise: Boolean = true,
    ): Plan {
        if (maxIndex <= 0) return Plan(raiseStreamTo = null, playerVolume = 1f)

        val current = currentIndex.coerceIn(0, maxIndex)
        val belowLoudest = requestedDb(requested)

        if (curve == null) {
            // Only the top of the stream has a loudness that needs no device to explain it,
            // so that is the reference the player attenuates from.
            val playerVolume = dbToAmplitude(belowLoudest)
            if (!mayRaise) return Plan(raiseStreamTo = null, playerVolume = playerVolume)
            return Plan(
                raiseStreamTo = maxIndex.takeIf { current < it },
                playerVolume = playerVolume,
            )
        }

        val targetDb = curve.dbAt(maxIndex) + belowLoudest
        val needed = curve.lowestIndexAtLeast(targetDb)
        // Never below where the phone already is: turning the shared stream down would turn
        // every other alarm clock on the phone down with it, for as long as ours plays.
        val streamAfter = if (mayRaise) maxOf(current, needed) else maxOf(current, 1)

        return Plan(
            raiseStreamTo = needed.takeIf { mayRaise && current < it },
            // Clamped at 1: the stream is the ceiling the phone sets, and no correction of
            // ours may push an alarm past it.
            playerVolume = dbToAmplitude(targetDb - curve.dbAt(streamAfter)),
        )
    }

    /**
     * What [plan] will actually deliver, in dB below the loudest alarm — the figure
     * [requestedDb] asked for. Exposed because "it came out at the level that was chosen"
     * is the property worth testing, and it cannot be read off either knob alone.
     */
    fun effectiveDb(plan: Plan, currentIndex: Int, maxIndex: Int, curve: Curve?): Float {
        if (maxIndex <= 0) return 0f
        val streamAfter = plan.raiseStreamTo ?: currentIndex.coerceIn(0, maxIndex)
        val streamDb = when {
            curve != null -> curve.dbAt(streamAfter) - curve.dbAt(maxIndex)
            // With no curve the stream is always brought to its maximum, which is the 0 dB
            // reference itself.
            else -> 0f
        }
        return streamDb + amplitudeToDb(plan.playerVolume)
    }

    private fun amplitudeToDb(amplitude: Float): Float = 20f * log10(amplitude)

    private fun dbToAmplitude(db: Float): Float = 10f.pow(db / 20f).coerceIn(0f, 1f)
}
