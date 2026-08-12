package com.faybish.vibealarm.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** App-wide preferences. Lives in device-protected storage like everything else. */
class SettingsStore(context: Context, scope: CoroutineScope) {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
    )

    val volumeKeysSnooze: Flow<Boolean> =
        dataStore.data.map { it[KEY_VOLUME_KEYS_SNOOZE] ?: true }

    val defaultSnoozeMinutes: Flow<Int> =
        dataStore.data.map { it[KEY_DEFAULT_SNOOZE_MINUTES] ?: 5 }

    val defaultSnoozeCount: Flow<Int> =
        dataStore.data.map { it[KEY_DEFAULT_SNOOZE_COUNT] ?: 3 }

    val onboardingDone: Flow<Boolean> =
        dataStore.data.map { it[KEY_ONBOARDING_DONE] ?: false }

    /**
     * Debug switch that pretends the device has no vibration amplitude control,
     * so the PWM emulation path can be felt on hardware that does have it.
     * Read synchronously from the alarm path, hence the cached mirror below.
     */
    val forcePwmFlow: Flow<Boolean> =
        dataStore.data.map { it[KEY_FORCE_PWM] ?: false }

    private val forcePwmState =
        forcePwmFlow.stateIn(scope, SharingStarted.Eagerly, false)

    val forcePwmEmulation: Boolean get() = forcePwmState.value

    suspend fun setVolumeKeysSnooze(enabled: Boolean) =
        put(KEY_VOLUME_KEYS_SNOOZE, enabled)

    suspend fun setForcePwmEmulation(enabled: Boolean) = put(KEY_FORCE_PWM, enabled)

    suspend fun setOnboardingDone(done: Boolean) = put(KEY_ONBOARDING_DONE, done)

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
        val KEY_DEFAULT_SNOOZE_MINUTES = intPreferencesKey("default_snooze_minutes")
        val KEY_DEFAULT_SNOOZE_COUNT = intPreferencesKey("default_snooze_count")
    }
}
