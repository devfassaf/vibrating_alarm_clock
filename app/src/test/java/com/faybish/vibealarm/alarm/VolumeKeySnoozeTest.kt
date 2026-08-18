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
    fun `an alarm's own choice wins over the global setting`() {
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = false, globalDefault = true)).isFalse()
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = true, globalDefault = false)).isTrue()
    }

    @Test
    fun `an alarm with no opinion follows the global setting`() {
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = null, globalDefault = true)).isTrue()
        assertThat(VolumeKeySnooze.enabledFor(perAlarm = null, globalDefault = false)).isFalse()
    }
}
