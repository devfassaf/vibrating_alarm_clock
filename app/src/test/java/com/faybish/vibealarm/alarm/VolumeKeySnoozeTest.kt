package com.faybish.vibealarm.alarm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Volume keys as snooze, on the rings where nothing is on screen to press.
 *
 * They used to be read by the ringing activity alone, so they worked exactly when that
 * activity had focus — which meant they worked once and then stopped, and never worked at all
 * for an alarm whose screen deliberately stays dark. A media session receives them with the
 * screen off, and that is what this covers: one press, one snooze, and no snooze from the
 * system merely asking what the volume is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeKeySnoozeTest {

    private lateinit var context: Context
    private var snoozes = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        snoozes = 0
    }

    private fun keys() = VolumeKeySnooze(context) { snoozes++ }

    @Test
    fun `a press in either direction snoozes`() {
        val provider = keys().volumeProvider()

        provider.onAdjustVolume(1)
        provider.onAdjustVolume(-1)

        assertThat(snoozes).isEqualTo(2)
    }

    /**
     * Direction zero is the system asking about the current volume, not a key press — a
     * snooze there would end the ring the moment the session was registered.
     */
    @Test
    fun `the system asking about the volume is not a press`() {
        keys().volumeProvider().onAdjustVolume(0)

        assertThat(snoozes).isEqualTo(0)
    }

    /** A slider dragged to a value is the same intent as a key press: make it stop. */
    @Test
    fun `setting the volume outright also snoozes`() {
        keys().volumeProvider().onSetVolumeTo(3)

        assertThat(snoozes).isEqualTo(1)
    }

    @Test
    fun `the session starts and stops cleanly, and starting twice is a no-op`() {
        val keys = keys()

        keys.start()
        keys.start()
        keys.stop()
        keys.stop()

        assertThat(snoozes).isEqualTo(0)
    }

    // --- whose choice it is ---

    @Test
    fun `an alarm's own choice wins over the global setting`() = kotlinx.coroutines.runBlocking {
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = false) { true }).isFalse()
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = true) { false }).isTrue()
    }

    @Test
    fun `an alarm with no opinion follows the global setting`() = kotlinx.coroutines.runBlocking {
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = null) { true }).isTrue()
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = null) { false }).isFalse()
    }

    /** The settings read runs on the alarm path, once per ring — only when actually needed. */
    @Test
    fun `an alarm that already decided never pays for the settings read`() =
        kotlinx.coroutines.runBlocking {
            var reads = 0

            VolumeKeySnooze.enabledFor(perAlarm = true) { reads++; true }
            VolumeKeySnooze.enabledFor(perAlarm = false) { reads++; true }
            assertThat(reads).isEqualTo(0)

            VolumeKeySnooze.enabledFor(perAlarm = null) { reads++; true }
            assertThat(reads).isEqualTo(1)
        }

    // --- the alarm-stream watcher: the half that works while a ringtone plays ---

    private fun audioManager(): android.media.AudioManager =
        context.getSystemService(android.media.AudioManager::class.java)

    private fun notifySettingsChanged() {
        context.contentResolver.notifyChange(android.provider.Settings.System.CONTENT_URI, null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `a stream level change while watching snoozes once`() {
        val keys = keys()
        keys.start(watchStream = true)

        audioManager().setStreamVolume(android.media.AudioManager.STREAM_ALARM, 5, 0)
        notifySettingsChanged()

        assertThat(snoozes).isEqualTo(1)
        keys.stop()
    }

    /**
     * The observer hears every Settings.System write — brightness, rotation, all of it.
     * Only the alarm stream actually moving may snooze; anything else at 6am is noise.
     */
    @Test
    fun `an unrelated settings change is not a press`() {
        val keys = keys()
        keys.start(watchStream = true)

        notifySettingsChanged()
        notifySettingsChanged()

        assertThat(snoozes).isEqualTo(0)
        keys.stop()
    }

    /** After stop, the restore of the stream must not snooze an alarm that already ended. */
    @Test
    fun `a stream change after stop is ignored`() {
        val keys = keys()
        keys.start(watchStream = true)
        keys.stop()

        audioManager().setStreamVolume(android.media.AudioManager.STREAM_ALARM, 2, 0)
        notifySettingsChanged()

        assertThat(snoozes).isEqualTo(0)
    }

    /**
     * The app's own mid-window ringtone stop restores the stream. That restore must be
     * invisible to the watcher, or a ringtone shorter than the pattern snoozes the alarm
     * the moment the sound ends — cutting the vibration short, which is the exact split
     * invariant 12 protects.
     */
    @Test
    fun `our own stream restore does not read as a press`() {
        val keys = keys()
        keys.start(watchStream = true)

        keys.withStreamChangeIgnored {
            audioManager().setStreamVolume(android.media.AudioManager.STREAM_ALARM, 1, 0)
            notifySettingsChanged()
        }
        notifySettingsChanged()

        assertThat(snoozes).isEqualTo(0)

        // And the watcher is alive again afterwards: a real press still snoozes.
        audioManager().setStreamVolume(android.media.AudioManager.STREAM_ALARM, 6, 0)
        notifySettingsChanged()
        assertThat(snoozes).isEqualTo(1)
        keys.stop()
    }

    /** A vibration-only ring registers no observer at all — nothing to misread, no cost. */
    @Test
    fun `without the stream watch a stream change does nothing`() {
        val keys = keys()
        keys.start(watchStream = false)

        audioManager().setStreamVolume(android.media.AudioManager.STREAM_ALARM, 4, 0)
        notifySettingsChanged()

        assertThat(snoozes).isEqualTo(0)
        keys.stop()
    }
}
