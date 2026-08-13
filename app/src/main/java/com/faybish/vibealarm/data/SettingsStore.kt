package com.faybish.vibealarm.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.faybish.vibealarm.domain.update.ReleaseInfo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/** App-wide preferences. Lives in device-protected storage like everything else. */
class SettingsStore(context: Context, scope: CoroutineScope) {

    /**
     * The file path is built by hand on purpose. `Context.preferencesDataStoreFile`
     * resolves through `applicationContext.filesDir`, and the Application object is
     * always credential-encrypted — so the convenient extension would put settings in
     * storage that cannot be read after a reboot until the user unlocks the phone,
     * exactly when the alarm needs them. [context] here is the device-protected one,
     * whose own `filesDir` is under /data/user_de.
     */
    @VisibleForTesting
    val file: File = File(context.filesDir, "datastore")
        .apply { mkdirs() }
        .resolve("$FILE_NAME.preferences_pb")

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file },
    )

    /**
     * A read failure degrades to the default rather than propagating: settings are
     * conveniences, and none of them is worth failing an alarm over.
     */
    private fun <T> read(default: T, extract: (Preferences) -> T?): Flow<T> =
        dataStore.data.map { extract(it) ?: default }.catch { emit(default) }

    val volumeKeysSnooze: Flow<Boolean> = read(true) { it[KEY_VOLUME_KEYS_SNOOZE] }

    val defaultSnoozeMinutes: Flow<Int> = read(5) { it[KEY_DEFAULT_SNOOZE_MINUTES] }

    val defaultSnoozeCount: Flow<Int> = read(3) { it[KEY_DEFAULT_SNOOZE_COUNT] }

    val onboardingDone: Flow<Boolean> = read(false) { it[KEY_ONBOARDING_DONE] }

    /**
     * Whether the time picker opens on the keypad instead of the clock face. Remembered
     * rather than offered as a setting: which one you prefer is something you demonstrate
     * by using it, and the next alarm should open the way the last one did.
     */
    val timeInputByKeyboard: Flow<Boolean> = read(false) { it[KEY_TIME_INPUT_KEYBOARD] }

    /**
     * Debug switch that pretends the device has no vibration amplitude control, so
     * the PWM emulation path can be felt on hardware that does have it. Mirrored
     * into state because the alarm path reads it synchronously.
     */
    val forcePwmFlow: Flow<Boolean> = read(false) { it[KEY_FORCE_PWM] }

    private val forcePwmState = forcePwmFlow.stateIn(scope, SharingStarted.Eagerly, false)

    val forcePwmEmulation: Boolean get() = forcePwmState.value

    suspend fun setVolumeKeysSnooze(enabled: Boolean) =
        put(KEY_VOLUME_KEYS_SNOOZE, enabled)

    suspend fun setForcePwmEmulation(enabled: Boolean) = put(KEY_FORCE_PWM, enabled)

    suspend fun setOnboardingDone(done: Boolean) = put(KEY_ONBOARDING_DONE, done)

    suspend fun setTimeInputByKeyboard(keyboard: Boolean) = put(KEY_TIME_INPUT_KEYBOARD, keyboard)

    // --- in-app updater state (see DataStoreUpdateStore) ---

    suspend fun updateSkippedVersion(): String? =
        dataStore.data.map { it[KEY_UPDATE_SKIPPED] }.catch { emit(null) }.first()

    suspend fun setUpdateSkippedVersion(version: String?) {
        dataStore.edit {
            if (version.isNullOrBlank()) it.remove(KEY_UPDATE_SKIPPED) else it[KEY_UPDATE_SKIPPED] = version
        }
    }

    suspend fun updateLastCheckAt(): Long =
        dataStore.data.map { it[KEY_UPDATE_LAST_CHECK] ?: 0L }.catch { emit(0L) }.first()

    suspend fun setUpdateLastCheckAt(millis: Long) {
        dataStore.edit { it[KEY_UPDATE_LAST_CHECK] = millis }
    }

    /** Cached so an offline open can still say what the newest known release was. */
    suspend fun updateCachedRelease(): ReleaseInfo? {
        val encoded = dataStore.data.map { it[KEY_UPDATE_RELEASE] }.catch { emit(null) }.first()
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ReleaseInfo>(encoded) }.getOrNull()
    }

    suspend fun setUpdateCachedRelease(release: ReleaseInfo?) {
        dataStore.edit {
            if (release == null) {
                it.remove(KEY_UPDATE_RELEASE)
            } else {
                it[KEY_UPDATE_RELEASE] = json.encodeToString(release)
            }
        }
    }

    suspend fun setDefaultSnooze(minutes: Int, count: Int) {
        dataStore.edit {
            it[KEY_DEFAULT_SNOOZE_MINUTES] = minutes
            it[KEY_DEFAULT_SNOOZE_COUNT] = count
        }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private companion object {
        const val FILE_NAME = "settings"
        val KEY_VOLUME_KEYS_SNOOZE = booleanPreferencesKey("volume_keys_snooze")
        val KEY_FORCE_PWM = booleanPreferencesKey("force_pwm_emulation")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val KEY_TIME_INPUT_KEYBOARD = booleanPreferencesKey("time_input_keyboard")
        val KEY_DEFAULT_SNOOZE_MINUTES = intPreferencesKey("default_snooze_minutes")
        val KEY_DEFAULT_SNOOZE_COUNT = intPreferencesKey("default_snooze_count")
        val KEY_UPDATE_SKIPPED = stringPreferencesKey("update_skipped_version")
        val KEY_UPDATE_LAST_CHECK = longPreferencesKey("update_last_check_at")
        val KEY_UPDATE_RELEASE = stringPreferencesKey("update_cached_release")

        val json = Json { ignoreUnknownKeys = true }
    }
}
