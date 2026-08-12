package com.faybish.vibealarm.domain.update

/** Where releases come from. Abstracted so the check flow is testable without a network. */
interface ReleaseSource {
    /** @return the published releases, newest first, or null when GitHub was unreachable. */
    suspend fun fetchReleases(): List<RawRelease>?
}

/** The updater's own persisted state. Backed by device-protected storage in production. */
interface UpdateStore {
    suspend fun skippedVersion(): String?
    suspend fun setSkippedVersion(version: String?)
    suspend fun cachedRelease(): ReleaseInfo?
    suspend fun setCachedRelease(release: ReleaseInfo?)
    suspend fun lastCheckAtMillis(): Long
    suspend fun setLastCheckAtMillis(millis: Long)
}

/**
 * Decides, on each app open, whether a newer release exists.
 *
 * Pure orchestration: no Android types, so the whole flow — including the "GitHub is
 * down" and "the release has no APK" paths — is covered by JVM tests.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val store: UpdateStore,
    private val installedVersion: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * @param force skip the duplicate-check guard; used by the manual button.
     */
    suspend fun check(silent: Boolean, force: Boolean = false): UpdateCheck {
        val local = installedVersion()
            ?: return UpdateCheck(UpdateStatus.UNAVAILABLE, installedVersion = null)

        // The app checks on every open, as intended. This only collapses the burst of
        // opens that are not really opens — a rotation, a back-and-forth to settings —
        // which would otherwise spend the unauthenticated GitHub budget on nothing.
        val sinceLast = now() - store.lastCheckAtMillis()
        if (!force && sinceLast in 0 until MIN_INTERVAL_MILLIS) {
            val cached = store.cachedRelease()
            return UpdateCheck(
                status = UpdateDecisions.resolveStatus(cached, local, store.skippedVersion(), silent),
                installedVersion = local,
                release = cached,
            )
        }

        val releases = source.fetchReleases()
        store.setLastCheckAtMillis(now())
        if (releases == null) {
            // Offline is not an error worth showing: fall back to whatever we last knew.
            val cached = store.cachedRelease()
            val status = if (cached == null) {
                UpdateStatus.UNAVAILABLE
            } else {
                UpdateDecisions.resolveStatus(cached, local, store.skippedVersion(), silent)
            }
            return UpdateCheck(status, local, cached)
        }

        val release = newestUsableRelease(releases, local)
        if (release != null) store.setCachedRelease(release)
        return UpdateCheck(
            status = UpdateDecisions.resolveStatus(release, local, store.skippedVersion(), silent),
            installedVersion = local,
            release = release,
        )
    }

    /** Marks a version as "do not remind me"; the manual check still surfaces it. */
    suspend fun skip(version: String) = store.setSkippedVersion(Versions.clean(version))

    /**
     * The newest release that a device could actually install: published, tagged with a
     * three-component version, and carrying an APK. A release failing any of those is
     * skipped rather than reported as an error — the next one down may well be fine.
     */
    private fun newestUsableRelease(releases: List<RawRelease>, local: String?): ReleaseInfo? {
        val candidate = releases.firstOrNull { !it.draft && !it.preRelease } ?: return null
        if (!Versions.isDeliverable(candidate.tag)) return null
        val asset = UpdateDecisions.pickApkAsset(candidate.assets, candidate.tag) ?: return null
        val version = Versions.clean(candidate.tag).removePrefix("v")
        return ReleaseInfo(
            version = version,
            tag = Versions.clean(candidate.tag),
            assetName = asset.name,
            assetUrl = asset.downloadUrl,
            sizeBytes = asset.sizeBytes,
            whatsNew = ReleaseNotes.whatsNew(releases, local),
        )
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 60_000L
    }
}
