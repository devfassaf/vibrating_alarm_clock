package com.faybish.vibealarm.alarm

import android.app.Application
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.domain.PatternSegment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The slider previews. Two things matter beyond "it vibrates": that the preview reflects
 * the alarm's own pattern rather than a generic buzz, and that it never runs while a real
 * alarm is ringing — there is one vibrator, and the preview also borrows the alarm stream
 * volume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewEngineTest {

    private lateinit var application: Application
    private lateinit var scope: CoroutineScope
    private lateinit var engine: PreviewEngine

    /** Robolectric's vibrator shadow reports the waveform timings and the live state. */
    private val vibrator
        get() = shadowOf(application.getSystemService(VibratorManager::class.java)!!.defaultVibrator)

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTests(application)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        engine = PreviewEngine(application, scope, AppGraph.reliabilityLogger)
    }

    @After
    fun tearDown() {
        engine.stop()
    }

    @Test
    fun `previewing a pattern gives one short burst that does not repeat`() {
        engine.previewVibration(
            segments = listOf(PatternSegment.vibrate(500, 200), PatternSegment.pause(700)),
            intensityScale = 1f,
        )
        shadowOf(application.mainLooper).idle()

        assertThat(vibrator.isVibrating).isTrue()
        // A burst, not the alarm's rhythm: the slider is about strength. The waveform may
        // be a single step or a run of PWM pulses depending on the hardware, so the
        // invariant is its total length.
        assertThat(vibrator.pattern.sum()).isEqualTo(700L)
        // -1 is "play once". A repeating preview would never stop.
        assertThat(vibrator.repeat).isEqualTo(-1)
    }

    /** On hardware that can vary strength, the burst is a single step at that amplitude. */
    @Test
    fun `with amplitude control the burst is one step`() {
        vibrator.setHasAmplitudeControl(true)

        engine.previewVibration(listOf(PatternSegment.vibrate(500, 200)), intensityScale = 1f)
        shadowOf(application.mainLooper).idle()

        assertThat(vibrator.pattern.toList()).containsExactly(700L)
    }

    /**
     * Without amplitude control the strength is emulated by chopping the burst, and the
     * proportion of "on" time is what the user feels as intensity.
     */
    @Test
    fun `without amplitude control the burst is emulated at the right duty cycle`() {
        vibrator.setHasAmplitudeControl(false)

        engine.previewVibration(listOf(PatternSegment.vibrate(500, 200)), intensityScale = 1f)
        shadowOf(application.mainLooper).idle()

        val timings = vibrator.pattern.toList()
        assertThat(timings.size).isGreaterThan(1)
        // The waveform starts with an on-phase; every other entry is off.
        val on = timings.filterIndexed { index, _ -> index % 2 == 0 }.sum()
        assertThat(on.toDouble() / timings.sum()).isWithin(0.08).of(200 / 255.0)
    }

    @Test
    fun `an empty pattern still gives feedback rather than nothing`() {
        engine.previewVibration(segments = emptyList(), intensityScale = 1f)
        shadowOf(application.mainLooper).idle()

        assertThat(vibrator.isVibrating).isTrue()
    }

    /**
     * The rule that protects a real alarm. AlarmRingingService publishes what it is
     * playing precisely so callers like this one can stay out of the way.
     */
    @Test
    fun `no preview happens while an alarm is ringing`() {
        AlarmRingingServiceTestAccess.setPlaying(7L)
        try {
            engine.previewVibration(
                segments = listOf(PatternSegment.vibrate(500, 200)),
                intensityScale = 1f,
            )
            engine.previewSound(ringtoneUri = null, volume = 1f)
            shadowOf(application.mainLooper).idle()

            assertThat(vibrator.isVibrating).isFalse()
        } finally {
            AlarmRingingServiceTestAccess.setPlaying(null)
        }
    }

    @Test
    fun `stopping cancels the vibration in flight`() {
        engine.previewVibration(
            segments = listOf(PatternSegment.vibrate(5_000, 200)),
            intensityScale = 1f,
        )
        shadowOf(application.mainLooper).idle()
        assertThat(vibrator.isVibrating).isTrue()

        engine.stop()
        shadowOf(application.mainLooper).idle()

        assertThat(vibrator.isVibrating).isFalse()
        assertThat(vibrator.isCancelled).isTrue()
    }

    /** Dragging a slider fires repeatedly; each preview must supersede the last. */
    @Test
    fun `a second preview cancels the first instead of layering on it`() {
        engine.previewVibration(listOf(PatternSegment.vibrate(5_000, 200)), intensityScale = 1f)
        shadowOf(application.mainLooper).idle()

        engine.previewVibration(listOf(PatternSegment.vibrate(400, 100)), intensityScale = 1f)
        shadowOf(application.mainLooper).idle()

        assertThat(vibrator.isCancelled).isTrue()
        assertThat(vibrator.isVibrating).isTrue()
    }
}
