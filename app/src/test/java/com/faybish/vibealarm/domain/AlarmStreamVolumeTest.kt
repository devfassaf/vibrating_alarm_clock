package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The per-alarm volume has one promise: the level chosen on the slider is the level that
 * comes out, whatever the phone's own alarm volume happens to be. These tests measure that
 * promise in decibels below the loudest alarm the phone can play, because that is the only
 * figure both knobs — the shared stream and our own player — can be compared in.
 *
 * The alarm stream is shared with every other alarm clock on the phone, so this app may
 * raise it and must never lower it.
 */
class AlarmStreamVolumeTest {

    /** A real phone's alarm steps, measured on an Android 15 device: 7 steps, ~30 dB. */
    private val sevenStep = curveOf(-29.7f, -22f, -17f, -13.2f, -9.9f, -4.8f, 0f)

    /** The other common shape: more steps over the same range. */
    private val fifteenStep = curveOf(
        -40f, -35.5f, -31.4f, -27.7f, -24.4f, -21.4f, -18.7f, -16.2f,
        -14f, -11.9f, -10f, -8f, -5.6f, -2.9f, 0f,
    )

    private fun curveOf(vararg db: Float): AlarmStreamVolume.Curve =
        checkNotNull(AlarmStreamVolume.Curve.of(db.size) { db[it - 1] })

    /** What the alarm will really come out at, relative to this phone's loudest alarm. */
    private fun delivered(
        requested: Float,
        currentIndex: Int,
        curve: AlarmStreamVolume.Curve?,
        maxIndex: Int,
    ): Float = AlarmStreamVolume.effectiveDb(
        plan = AlarmStreamVolume.plan(requested, currentIndex, maxIndex, curve),
        currentIndex = currentIndex,
        maxIndex = maxIndex,
        curve = curve,
    )

    // --- the promise ---

    /**
     * The one that matters. Wherever the phone's own alarm volume sits, the alarm comes out
     * at the level the slider was left on.
     */
    @Test
    fun `the alarm comes out at the chosen level from any system volume`() {
        listOf(sevenStep to 7, fifteenStep to 15).forEach { (curve, max) ->
            listOf(0f, 0.1f, 0.25f, 0.5f, 0.7f, 0.85f, 1f).forEach { requested ->
                (0..max).forEach { current ->
                    val actual = delivered(requested, current, curve, max)

                    assertWithMessage("%s steps, %s%% at system index %s", max, requested, current)
                        .that(actual)
                        .isWithin(0.01f)
                        .of(AlarmStreamVolume.requestedDb(requested))
                }
            }
        }
    }

    /** 100% means the loudest this phone plays an alarm, and nothing beyond it. */
    @Test
    fun `full volume is the phone's loudest alarm`() {
        assertThat(AlarmStreamVolume.requestedDb(1f)).isEqualTo(0f)
        (0..7).forEach { current ->
            assertThat(delivered(1f, current, sevenStep, maxIndex = 7)).isWithin(0.01f).of(0f)
        }
    }

    /**
     * The defect that started this: the chosen value used to be snapped to the stream's own
     * steps, so on a 7-step phone every value from 65% to 78% produced one identical sound
     * and the slider looked broken. Every position has to be its own loudness.
     */
    @Test
    fun `every position on the slider is a different loudness`() {
        listOf(sevenStep to 7, fifteenStep to 15).forEach { (curve, max) ->
            var previous = Float.NEGATIVE_INFINITY
            var value = 0f
            while (value <= 1f) {
                val actual = delivered(value, currentIndex = max, curve = curve, maxIndex = max)

                assertWithMessage("%s steps, %s%%", max, value).that(actual).isGreaterThan(previous)
                previous = actual
                value += 0.01f
            }
        }
    }

    /**
     * And evenly, so that moving the slider by the same amount is the same change wherever
     * it is moved. A slider whose top third is inaudibly different from full is a slider
     * that looks like it does nothing.
     */
    @Test
    fun `the same distance on the slider is the same change in loudness`() {
        val step = 0.1f
        val changes = (0..9).map { position ->
            val from = position * step
            AlarmStreamVolume.requestedDb(from + step) - AlarmStreamVolume.requestedDb(from)
        }

        changes.forEach { assertThat(it).isWithin(0.01f).of(AlarmStreamVolume.RANGE_DB * step) }
    }

    /** The two the user compared, on the phone that reported them as the same sound. */
    @Test
    fun `ten percent is far quieter than a hundred`() {
        val quiet = delivered(0.1f, currentIndex = 7, curve = sevenStep, maxIndex = 7)
        val loud = delivered(1f, currentIndex = 7, curve = sevenStep, maxIndex = 7)

        assertThat(loud - quiet).isWithin(0.01f).of(0.9f * AlarmStreamVolume.RANGE_DB)
    }

    // --- the shared stream ---

    /** The case that looked like "VibeAlarm broke my built-in alarm". */
    @Test
    fun `a quiet alarm does not turn the shared stream down`() {
        val plan = AlarmStreamVolume.plan(0.3f, currentIndex = 7, maxIndex = 7, curve = sevenStep)

        assertThat(plan.raiseStreamTo).isNull()
        assertThat(plan.playerVolume).isLessThan(1f)
    }

    @Test
    fun `the stream is never asked to go below where the phone has it`() {
        listOf(sevenStep to 7, fifteenStep to 15).forEach { (curve, max) ->
            listOf(0f, 0.2f, 0.5f, 0.9f, 1f).forEach { requested ->
                (0..max).forEach { current ->
                    val plan = AlarmStreamVolume.plan(requested, current, max, curve)

                    assertWithMessage("%s%% at index %s", requested, current)
                        .that(plan.raiseStreamTo ?: max).isAtLeast(current)
                }
            }
        }
    }

    /** A stream too quiet for the alarm is raised — but only as far as the alarm needs. */
    @Test
    fun `a stream too quiet is raised no further than necessary`() {
        val plan = AlarmStreamVolume.plan(0.5f, currentIndex = 1, maxIndex = 7, curve = sevenStep)

        assertThat(plan.raiseStreamTo).isNotNull()
        assertThat(plan.raiseStreamTo!!).isGreaterThan(1)
        assertThat(plan.raiseStreamTo!!).isLessThan(7)
    }

    @Test
    fun `a muted stream is raised out of silence`() {
        val plan = AlarmStreamVolume.plan(0.8f, currentIndex = 0, maxIndex = 7, curve = sevenStep)

        assertThat(plan.raiseStreamTo).isAtLeast(1)
    }

    // --- the device that will not answer ---

    /**
     * The phone this bug was reported from. It answers `getStreamVolumeDb` with the same
     * figure for every step, so the old code computed "no attenuation needed" every time
     * and 10% came out exactly as loud as 100%. A curve that describes no range of loudness
     * is not believed, and the volume is then delivered entirely by the player.
     */
    @Test
    fun `a device reporting one figure for every step is not believed`() {
        val flat = AlarmStreamVolume.Curve.of(maxIndex = 7) { 0f }

        assertThat(flat).isNull()

        val plan = AlarmStreamVolume.plan(0.1f, currentIndex = 7, maxIndex = 7, curve = null)
        assertThat(plan.playerVolume).isLessThan(0.1f)
        assertThat(AlarmStreamVolume.effectiveDb(plan, 7, 7, curve = null))
            .isWithin(0.01f).of(AlarmStreamVolume.requestedDb(0.1f))
    }

    /** With no usable curve, only the top of the stream has a loudness we can reason about. */
    @Test
    fun `without a curve the stream goes to the level the player measures from`() {
        val plan = AlarmStreamVolume.plan(0.1f, currentIndex = 3, maxIndex = 7, curve = null)

        assertThat(plan.raiseStreamTo).isEqualTo(7)
        assertThat(AlarmStreamVolume.effectiveDb(plan, 3, 7, curve = null))
            .isWithin(0.01f).of(AlarmStreamVolume.requestedDb(0.1f))
    }

    @Test
    fun `a curve that does not rise with the index is not believed`() {
        val wrongWay = AlarmStreamVolume.Curve.of(maxIndex = 3) { index ->
            floatArrayOf(0f, -20f, -40f)[index - 1]
        }

        assertThat(wrongWay).isNull()
    }

    /** Some devices report silence as -infinity for the quietest step. */
    @Test
    fun `a device answering with infinity is ignored rather than trusted`() {
        val infinite = AlarmStreamVolume.Curve.of(maxIndex = 3) { index ->
            if (index == 1) Float.NEGATIVE_INFINITY else 0f
        }
        val notANumber = AlarmStreamVolume.Curve.of(maxIndex = 3) { Float.NaN }

        assertThat(infinite).isNull()
        assertThat(notANumber).isNull()
    }

    @Test
    fun `a device that declines to answer is not believed`() {
        assertThat(AlarmStreamVolume.Curve.of(maxIndex = 7) { null }).isNull()
    }

    @Test
    fun `a device reporting no volume steps is left alone`() {
        assertThat(AlarmStreamVolume.Curve.of(maxIndex = 0) { 0f }).isNull()

        val plan = AlarmStreamVolume.plan(0.5f, currentIndex = 0, maxIndex = 0, curve = null)
        assertThat(plan.raiseStreamTo).isNull()
        assertThat(plan.playerVolume).isEqualTo(1f)
    }

    // --- the edges of the slider ---

    /** The bottom of the slider is quiet, not silent: an alarm has to make a sound. */
    @Test
    fun `zero still asks for an audible alarm`() {
        listOf(sevenStep to 7, fifteenStep to 15, null to 7).forEach { (curve, max) ->
            val plan = AlarmStreamVolume.plan(0f, currentIndex = max, maxIndex = max, curve = curve)

            assertThat(plan.playerVolume).isGreaterThan(0f)
            assertThat(plan.raiseStreamTo ?: max).isAtLeast(1)
        }
        assertThat(AlarmStreamVolume.requestedDb(0f)).isEqualTo(-AlarmStreamVolume.RANGE_DB)
    }

    @Test
    fun `a value outside the slider is clamped rather than trusted`() {
        assertThat(AlarmStreamVolume.requestedDb(2f)).isEqualTo(0f)
        assertThat(AlarmStreamVolume.plan(-1f, 7, 7, sevenStep).playerVolume)
            .isEqualTo(AlarmStreamVolume.plan(0f, 7, 7, sevenStep).playerVolume)
    }

    // --- a phone that refuses to let us touch the stream ---

    /**
     * When the write is refused the phone's own level becomes the ceiling — that much is
     * out of our hands — but the alarm must still play, and never louder than the phone
     * allows.
     */
    @Test
    fun `a stream that cannot be moved still plays, within the phone's ceiling`() {
        (0..7).forEach { current ->
            val plan =
                AlarmStreamVolume.plan(0.5f, current, maxIndex = 7, curve = sevenStep, mayRaise = false)

            assertWithMessage("index %s", current).that(plan.raiseStreamTo).isNull()
            assertWithMessage("index %s", current).that(plan.playerVolume).isGreaterThan(0f)
            assertWithMessage("index %s", current).that(plan.playerVolume).isAtMost(1f)
        }
    }

    /** Loud enough to reach the chosen level: exact, just as if we had raised it ourselves. */
    @Test
    fun `a stream already loud enough reaches the chosen level without being moved`() {
        val plan =
            AlarmStreamVolume.plan(0.5f, currentIndex = 7, maxIndex = 7, curve = sevenStep, mayRaise = false)

        assertThat(AlarmStreamVolume.effectiveDb(plan, 7, 7, sevenStep))
            .isWithin(0.01f).of(AlarmStreamVolume.requestedDb(0.5f))
    }
}
