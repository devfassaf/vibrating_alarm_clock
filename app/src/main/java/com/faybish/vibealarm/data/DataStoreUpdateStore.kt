package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.update.ReleaseInfo
import com.faybish.vibealarm.domain.update.UpdateStore

/**
 * The updater's state, kept alongside the app's other settings in device-protected
 * storage. Nothing here is on the alarm path, but it shares the same storage rule so a
 * stray read can never be the thing that breaks a morning.
 */
class DataStoreUpdateStore(private val settings: SettingsStore) : UpdateStore {

    override suspend fun skippedVersion(): String? = settings.updateSkippedVersion()

    override suspend fun setSkippedVersion(version: String?) =
        settings.setUpdateSkippedVersion(version)

    override suspend fun cachedRelease(): ReleaseInfo? = settings.updateCachedRelease()

    override suspend fun setCachedRelease(release: ReleaseInfo?) =
        settings.setUpdateCachedRelease(release)

    override suspend fun lastCheckAtMillis(): Long = settings.updateLastCheckAt()

    override suspend fun setLastCheckAtMillis(millis: Long) = settings.setUpdateLastCheckAt(millis)
}
