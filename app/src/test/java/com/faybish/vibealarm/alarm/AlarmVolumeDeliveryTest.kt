package com.faybish.vibealarm.alarm

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.domain.AlarmStreamVolume
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
import kotlin.math.log10

/**
 * The chosen volume, measured where it actually lands.
 *
 * [com.faybish.vibealarm.domain.AlarmStreamVolume] works out what the two knobs should be;
 * this is the other half — that the engine reads the phone's real state into that decision
 * and then applies both halves, to the shared stream and to its own player.
 *
 * These fail against the code they replaced, which snapped the chosen percentage to the
 * stream's own steps: on this device's curve 10% snapped to the bottom step and came out at
 * -85 dB, inaudible rather than quiet, while a phone that reports the same figure for every
 * step got no attenuation at all and played 10% as loud as 100%. Both are the same defect —
 * the level that comes out is not the level that was chosen — and the curve is only one of
 * the two devices it can land on. The other lives in AlarmStreamVolumeTest, which can hand
 * the decision a device that will not describe its own steps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmVolumeDeliveryTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private var maxIndex = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        audioManager = context.getSystemService(AudioManager::class.java)
        maxIndex = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(RINGTONE_URI), MediaInfo(60_000, 0))
    }

    private fun engine() = SoundEngine(
        context = context,
        logger = ReliabilityLogger(FakeVolumeLogDao(), CoroutineScope(Dispatchers.Unconfined)),
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    /** The engine keeps its player private; a test reads it rather than widening the API. */
    private fun SoundEngine.playerVolume(): Float {
        val field = SoundEngine::class.java.getDeclaredField("player")
        field.isAccessible = true
        return shadowOf(field.get(this) as MediaPlayer).leftVolume
    }

    /** Where the ring lands, in dB below the loudest alarm this phone plays. */
    private fun deliveredDb(requested: Float, systemIndex: Int): Float {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, systemIndex, 0)
        val engine = engine()
        engine.play(RINGTONE_URI, volume = requested)
        val player = engine.playerVolume()
        val stream = audioManager.getStreamVolumeDb(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        engine.stop()
        return stream + 20f * log10(player)
    }

    /**
     * The bug as reported: on a phone already turned up, every volume came out the same.
     * The quiet one has to reach the player, because the stream is the one thing we may
     * not turn down.
     */
    @Test
    fun `a quiet alarm on a loud phone is quiet, not full volume`() {
        val quiet = deliveredDb(requested = 0.1f, systemIndex = maxIndex)
        val loud = deliveredDb(requested = 1f, systemIndex = maxIndex)

        assertThat(loud).isWithin(0.1f).of(AlarmStreamVolume.requestedDb(1f))
        assertThat(quiet).isWithin(0.1f).of(AlarmStreamVolume.requestedDb(0.1f))
    }

    /** And the whole slider, not just its ends: every position its own loudness. */
    @Test
    fun `each setting delivers its own volume`() {
        var previous = Float.NEGATIVE_INFINITY
        listOf(0f, 0.1f, 0.3f, 0.5f, 0.65f, 0.78f, 0.9f, 1f).forEach { requested ->
            val delivered = deliveredDb(requested, systemIndex = maxIndex)

            assertWithMessage("%s%%", requested)
                .that(delivered).isWithin(0.1f).of(AlarmStreamVolume.requestedDb(requested))
            assertWithMessage("%s%%", requested).that(delivered).isGreaterThan(previous)
            previous = delivered
        }
    }

    /** A phone turned down is brought up to the level the player measures against. */
    @Test
    fun `a quiet phone is raised for the ring and handed back afterwards`() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
        val engine = engine()

        engine.play(RINGTONE_URI, volume = 0.5f)
        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isGreaterThan(1)

        engine.stop()
        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(1)
    }

    /** A muted stream makes no sound at the level it reports, so it is raised out of it. */
    @Test
    fun `a muted alarm stream is unmuted and raised`() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_MUTE, 0)
        val engine = engine()

        engine.play(RINGTONE_URI, volume = 1f)

        assertThat(audioManager.isStreamMute(AudioManager.STREAM_ALARM)).isFalse()
        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(maxIndex)
        engine.stop()
    }

    /**
     * Nothing played, so nothing should be left turned up: a ring with no playable source
     * used to hand the phone back only when the window ended.
     */
    @Test
    fun `a ring that cannot play anything gives the stream back at once`() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        val engine = engine()

        val played = engine.play(UNPLAYABLE_URI, volume = 1f)

        assertThat(played).isFalse()
        assertThat(audioManager.getStreamVolume(AudioManager.STREAM_ALARM)).isEqualTo(2)
    }

    private companion object {
        const val RINGTONE_URI = "content://media/internal/audio/media/7"
        const val UNPLAYABLE_URI = "content://media/internal/audio/media/nothing-here"
    }
}

/** The logger needs a dao; these tests are about loudness, not about the log. */
private class FakeVolumeLogDao : com.faybish.vibealarm.data.LogDao {
    override suspend fun insert(entry: com.faybish.vibealarm.data.ReliabilityLogEntity) = Unit

    override fun observeRecent(limit: Int) =
        kotlinx.coroutines.flow.flowOf(emptyList<com.faybish.vibealarm.data.ReliabilityLogEntity>())

    override suspend fun latest(event: String): com.faybish.vibealarm.data.ReliabilityLogEntity? = null

    override suspend fun pruneOlderThan(olderThan: Long) = Unit
}
