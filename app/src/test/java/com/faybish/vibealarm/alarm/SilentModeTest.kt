package com.faybish.vibealarm.alarm

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.domain.PatternSegment
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowMediaPlayer.MediaInfo
import org.robolectric.shadows.util.DataSource

/**
 * "Silent" means do not ring for other people. It does not mean do not wake me up.
 *
 * Both engines go out with alarm usage, which the platform routes past the ringer mode —
 * but that is a property of two attribute objects buried in two files, and a well-meant
 * refactor could quietly drop either one. These tests fail if that happens, in every
 * ringer mode the phone has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilentModeTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager

    private val segments = listOf(
        PatternSegment.vibrate(durationMs = 400, amplitude = 200),
        PatternSegment.pause(durationMs = 200),
        PatternSegment.vibrate(durationMs = 400, amplitude = 255),
    )

    /** Every mode the ringer can be in, including the two that mute other apps. */
    private val everyRingerMode = mapOf(
        "silent" to AudioManager.RINGER_MODE_SILENT,
        "vibrate only" to AudioManager.RINGER_MODE_VIBRATE,
        "normal" to AudioManager.RINGER_MODE_NORMAL,
    )

    private fun vibrator(): Vibrator = context.getSystemService(Vibrator::class.java)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        audioManager = context.getSystemService(AudioManager::class.java)
    }

    // --- vibration ---

    @Test
    fun `the pattern plays in every ringer mode, silent included`() {
        everyRingerMode.forEach { (name, mode) ->
            audioManager.ringerMode = mode
            val engine = VibrationEngine(context)

            val durationMs = engine.play(segments, intensityScale = 1f)

            assertWithMessage("ringer mode: $name").that(durationMs).isGreaterThan(0L)
            assertWithMessage("ringer mode: $name").that(shadowOf(vibrator()).isVibrating).isTrue()
            engine.stop()
        }
    }

    /**
     * The attribute that does the work. Alarm usage is what the vibrator service checks
     * before honouring the ringer mode and Do Not Disturb.
     */
    @Test
    fun `the vibration is requested as an alarm, not as a notification`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        VibrationEngine(context).play(segments, intensityScale = 1f)

        // The shadow types this as Object because the class only exists from API 33.
        val attributes = shadowOf(vibrator()).vibrationAttributesFromLastVibration
        assertThat(attributes).isInstanceOf(VibrationAttributes::class.java)
        assertThat((attributes as VibrationAttributes).usage)
            .isEqualTo(VibrationAttributes.USAGE_ALARM)
    }

    /** The live buzz while recording a pattern goes out the same way. */
    @Test
    fun `the recorder preview is also an alarm vibration`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        VibrationEngine(context).startPreview(amplitude = 180)

        assertThat(shadowOf(vibrator()).isVibrating).isTrue()
        val attributes = shadowOf(vibrator()).vibrationAttributesFromLastVibration
        assertThat((attributes as VibrationAttributes).usage)
            .isEqualTo(VibrationAttributes.USAGE_ALARM)
    }

    // --- sound ---

    private fun soundEngine() = SoundEngine(
        context = context,
        logger = ReliabilityLogger(FakeLogDao(), CoroutineScope(Dispatchers.Unconfined)),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun registerPlayableRingtone() {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(RINGTONE_URI),
            MediaInfo(60_000, 0),
        )
    }

    @Test
    fun `the ringtone plays in every ringer mode, silent included`() {
        registerPlayableRingtone()
        everyRingerMode.forEach { (name, mode) ->
            audioManager.ringerMode = mode
            val engine = soundEngine()

            val started = engine.play(RINGTONE_URI, volume = 0.8f)

            assertWithMessage("ringer mode: $name").that(started).isTrue()
            engine.stop()
        }
    }

    /** The attribute that does it: alarm usage, which the platform routes to the alarm
     *  stream — the one stream the ringer mode does not govern. */
    @Test
    fun `the ringtone is played with alarm usage on the alarm stream`() {
        val attributes = SoundEngine.ALARM_ATTRIBUTES

        assertThat(attributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(attributes.volumeControlStream).isEqualTo(AudioManager.STREAM_ALARM)
        assertThat(attributes.contentType).isEqualTo(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    }

    /**
     * The per-alarm volume is applied by moving the alarm stream, so a phone left on
     * silent still comes up to the level the user chose for this alarm.
     */
    @Test
    fun `the alarm stream is raised to the chosen level even from silent`() {
        registerPlayableRingtone()
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        val engine = soundEngine()
        engine.play(RINGTONE_URI, volume = 1f)

        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(max)

        // And it is handed back afterwards: the alarm borrows the stream, it does not keep it.
        engine.stop()
        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
    }

    /** The bottom of the slider is quiet, not mute. */
    @Test
    fun `the lowest volume setting still makes a sound`() {
        registerPlayableRingtone()
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)

        val engine = soundEngine()
        engine.play(RINGTONE_URI, volume = 0f)

        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isAtLeast(1)
        engine.stop()
    }

    private companion object {
        const val RINGTONE_URI = "content://media/internal/audio/media/7"
    }
}

/** The logger needs a dao; these tests are about output, not about the log. */
private class FakeLogDao : com.faybish.vibealarm.data.LogDao {
    override suspend fun insert(entry: com.faybish.vibealarm.data.ReliabilityLogEntity) = Unit

    override fun observeRecent(limit: Int) =
        kotlinx.coroutines.flow.flowOf(emptyList<com.faybish.vibealarm.data.ReliabilityLogEntity>())

    override suspend fun latest(event: String): com.faybish.vibealarm.data.ReliabilityLogEntity? = null

    override suspend fun pruneOlderThan(olderThan: Long) = Unit
}
