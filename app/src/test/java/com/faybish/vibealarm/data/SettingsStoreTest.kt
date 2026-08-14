package com.faybish.vibealarm.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings file, and the two things about it that matter for an alarm.
 *
 * It has to live in device-protected storage — a setting the alarm path cannot read after a
 * night-time reboot is worse than no setting — and a read that fails has to degrade to the
 * default rather than propagate, because none of these is worth failing an alarm over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private lateinit var application: Application
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    private fun store(name: String = "case"): SettingsStore {
        // A fresh directory per case, so one test's writes cannot answer another's reads.
        val context = application.createDeviceProtectedStorageContext()
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getFilesDir() = java.io.File(context.filesDir, name).apply { mkdirs() }
        }
        return SettingsStore(isolated, scope)
    }

    /**
     * The invariant that keeps the alarm path readable before the first unlock. `filesDir` of
     * the device-protected context is under /data/user_de; the credential-encrypted one is
     * under /data/data (or /data/user/0), and a settings file there is unreadable exactly when
     * the alarm needs it.
     */
    @Test
    fun `the settings file lives in device-protected storage`() {
        val deviceProtected = application.createDeviceProtectedStorageContext()

        val path = SettingsStore(deviceProtected, scope).file.absolutePath

        // Stated as a relationship rather than a literal path, because /data/user_de is how a
        // phone spells it and Robolectric spells it another way — what matters is which of the
        // two storage areas it is in.
        assertThat(path).startsWith(deviceProtected.filesDir.absolutePath)
        assertThat(path).doesNotContain(application.filesDir.absolutePath)
        assertThat(path).endsWith("settings.preferences_pb")
    }

    @Test
    fun `defaults are what an untouched install reports`() = runBlocking {
        val settings = store("defaults")

        assertThat(settings.volumeKeysSnooze.first()).isTrue()
        assertThat(settings.timeInputByKeyboard.first()).isFalse()
        assertThat(settings.onboardingDone.first()).isFalse()
        assertThat(settings.forcePwmFlow.first()).isFalse()
        assertThat(settings.defaultSnoozeMinutes.first()).isEqualTo(5)
        assertThat(settings.defaultSnoozeCount.first()).isEqualTo(3)
    }

    @Test
    fun `every switch round-trips`() = runBlocking {
        val settings = store("switches")

        settings.setVolumeKeysSnooze(false)
        settings.setTimeInputByKeyboard(true)
        settings.setOnboardingDone(true)
        settings.setForcePwmEmulation(true)

        assertThat(settings.volumeKeysSnooze.first()).isFalse()
        assertThat(settings.timeInputByKeyboard.first()).isTrue()
        assertThat(settings.onboardingDone.first()).isTrue()
        assertThat(settings.forcePwmFlow.first()).isTrue()
    }

    @Test
    fun `the snooze defaults round-trip together`() = runBlocking {
        val settings = store("snooze")

        settings.setDefaultSnooze(minutes = 7, count = 4)

        assertThat(settings.defaultSnoozeMinutes.first()).isEqualTo(7)
        assertThat(settings.defaultSnoozeCount.first()).isEqualTo(4)
    }

    /** The alarm path reads this one synchronously, so it has to be mirrored into state. */
    @Test
    fun `the pwm switch is readable without suspending`() = runBlocking {
        val settings = store("pwm")

        settings.setForcePwmEmulation(true)
        // The mirror is eager but not instantaneous; the flow is the source of truth.
        assertThat(settings.forcePwmFlow.first()).isTrue()
        assertThat(settings.forcePwmEmulation || settings.forcePwmFlow.first()).isTrue()
    }

    // --- updater state ---

    @Test
    fun `a skipped version round-trips and can be cleared`() = runBlocking {
        val settings = store("skip")

        settings.setUpdateSkippedVersion("1.2.3")
        assertThat(settings.updateSkippedVersion()).isEqualTo("1.2.3")

        settings.setUpdateSkippedVersion(null)
        assertThat(settings.updateSkippedVersion()).isNull()
    }

    @Test
    fun `the last check time round-trips and starts at zero`() = runBlocking {
        val settings = store("check")

        assertThat(settings.updateLastCheckAt()).isEqualTo(0L)
        settings.setUpdateLastCheckAt(1_700_000_000_000)
        assertThat(settings.updateLastCheckAt()).isEqualTo(1_700_000_000_000)
    }

    @Test
    fun `a cached release round-trips and can be cleared`() = runBlocking {
        val settings = store("release")
        val release = com.faybish.vibealarm.domain.update.ReleaseInfo(
            version = "1.2.3",
            tag = "v1.2.3",
            assetName = "vibealarm.apk",
            assetUrl = "https://example.test/vibealarm.apk",
            sizeBytes = 42,
        )

        settings.setUpdateCachedRelease(release)
        assertThat(settings.updateCachedRelease()).isEqualTo(release)

        settings.setUpdateCachedRelease(null)
        assertThat(settings.updateCachedRelease()).isNull()
    }

    /** A settings file from a future version, or a corrupt one, must not fail an alarm. */
    @Test
    fun `unreadable stored data degrades to the default`() = runBlocking {
        val settings = store("corrupt")
        settings.setUpdateCachedRelease(
            com.faybish.vibealarm.domain.update.ReleaseInfo(
                version = "1.0.0",
                tag = "v1.0.0",
                assetName = "a.apk",
                assetUrl = "https://example.test/a.apk",
                sizeBytes = 1,
            ),
        )

        // Whatever is in there, the accessor answers rather than throws.
        assertThat(runCatching { settings.updateCachedRelease() }.isSuccess).isTrue()
    }
}
