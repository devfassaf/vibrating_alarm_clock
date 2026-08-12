package com.faybish.vibealarm.domain.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The whole check flow, including every way it can go wrong. Pure, because
 * [UpdateChecker] deliberately has no Android types in it.
 */
class UpdateCheckerTest {

    private class FakeSource(var releases: List<RawRelease>?) : ReleaseSource {
        var calls = 0
        override suspend fun fetchReleases(): List<RawRelease>? {
            calls++
            return releases
        }
    }

    private class FakeStore : UpdateStore {
        var skipped: String? = null
        var cached: ReleaseInfo? = null
        var lastCheck = 0L
        override suspend fun skippedVersion() = skipped
        override suspend fun setSkippedVersion(version: String?) { skipped = version }
        override suspend fun cachedRelease() = cached
        override suspend fun setCachedRelease(release: ReleaseInfo?) { cached = release }
        override suspend fun lastCheckAtMillis() = lastCheck
        override suspend fun setLastCheckAtMillis(millis: Long) { lastCheck = millis }
    }

    private fun raw(
        version: String,
        assets: List<ReleaseAsset> = listOf(
            ReleaseAsset("vibealarm-v$version.apk", "https://example.test/$version.apk", 4_000),
        ),
        body: String? = "## מה חדש\n\n- שיפור כלשהו",
        draft: Boolean = false,
        preRelease: Boolean = false,
    ) = RawRelease("v$version", body, draft, preRelease, assets)

    private var clock = 1_000_000L

    private fun checker(source: FakeSource, store: FakeStore, installed: String? = "1.0.0") =
        UpdateChecker(source, store, installedVersion = { installed }, now = { clock })

    @Test
    fun `a newer release is reported with its asset and notes`() = runTest {
        val source = FakeSource(listOf(raw("1.0.1"), raw("1.0.0")))
        val store = FakeStore()

        val result = checker(source, store).check(silent = true)

        assertThat(result.status).isEqualTo(UpdateStatus.AVAILABLE)
        assertThat(result.release?.version).isEqualTo("1.0.1")
        assertThat(result.release?.assetName).isEqualTo("vibealarm-v1.0.1.apk")
        assertThat(result.release?.sizeBytes).isEqualTo(4_000)
        // Only versions the device would gain, not the one it already runs.
        assertThat(result.release?.whatsNew?.map { it.version }).containsExactly("1.0.1")
        assertThat(store.cached?.version).isEqualTo("1.0.1")
    }

    @Test
    fun `nothing newer is up to date`() = runTest {
        val source = FakeSource(listOf(raw("1.0.0")))
        val result = checker(source, FakeStore()).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.UP_TO_DATE)
    }

    @Test
    fun `drafts and pre-releases are ignored`() = runTest {
        val source = FakeSource(
            listOf(
                raw("2.0.0", draft = true),
                raw("1.5.0", preRelease = true),
                raw("1.0.1"),
            ),
        )
        val result = checker(source, FakeStore()).check(silent = true)
        assertThat(result.release?.version).isEqualTo("1.0.1")
    }

    /**
     * The newest release having no APK is a broken release, not a reason to hand the
     * user an older version they may already have — and definitely not a crash.
     */
    @Test
    fun `a newest release without an apk yields no update`() = runTest {
        val source = FakeSource(
            listOf(
                raw("1.0.1", assets = listOf(ReleaseAsset("notes.txt", "https://example.test/n.txt", 1))),
                raw("1.0.0"),
            ),
        )
        val result = checker(source, FakeStore()).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.UP_TO_DATE)
        assertThat(result.release).isNull()
    }

    @Test
    fun `an undeliverable four-component tag is not offered`() = runTest {
        val source = FakeSource(listOf(RawRelease("v1.0.0.1", null, false, false, emptyList())))
        val result = checker(source, FakeStore()).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.UP_TO_DATE)
    }

    @Test
    fun `no releases at all is not an error`() = runTest {
        val result = checker(FakeSource(emptyList()), FakeStore()).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.UP_TO_DATE)
    }

    // --- offline ---

    @Test
    fun `an unreachable GitHub falls back to the last known release`() = runTest {
        val store = FakeStore().apply {
            cached = ReleaseInfo("1.0.1", "v1.0.1", "vibealarm-v1.0.1.apk", "https://example.test/a.apk", 10)
        }
        val result = checker(FakeSource(releases = null), store).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.AVAILABLE)
        assertThat(result.release?.version).isEqualTo("1.0.1")
    }

    @Test
    fun `an unreachable GitHub with nothing cached reports unavailable, never an error`() = runTest {
        val result = checker(FakeSource(releases = null), FakeStore()).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.UNAVAILABLE)
        assertThat(result.release).isNull()
    }

    @Test
    fun `an unknown installed version cannot produce an update`() = runTest {
        val source = FakeSource(listOf(raw("9.9.9")))
        val result = checker(source, FakeStore(), installed = null).check(silent = true)
        assertThat(result.status).isEqualTo(UpdateStatus.UNAVAILABLE)
        assertThat(source.calls).isEqualTo(0)
    }

    // --- skipping ---

    @Test
    fun `a skipped version is hidden from the automatic check and shown to the manual one`() = runTest {
        val source = FakeSource(listOf(raw("1.0.1")))
        val store = FakeStore()
        val checker = checker(source, store)

        checker.skip("1.0.1")
        clock += 120_000
        assertThat(checker.check(silent = true).status).isEqualTo(UpdateStatus.SKIPPED)

        clock += 120_000
        assertThat(checker.check(silent = false).status).isEqualTo(UpdateStatus.AVAILABLE)
    }

    // --- the duplicate-check guard ---

    @Test
    fun `two opens a minute apart hit the network twice`() = runTest {
        val source = FakeSource(listOf(raw("1.0.1")))
        val store = FakeStore()
        val checker = checker(source, store)

        checker.check(silent = true)
        clock += 61_000
        checker.check(silent = true)

        assertThat(source.calls).isEqualTo(2)
    }

    /**
     * A rotation or a quick trip to settings and back is not really a new open, and
     * spending the unauthenticated GitHub budget on it buys nothing.
     */
    @Test
    fun `a second check within the same minute reuses the cached answer`() = runTest {
        val source = FakeSource(listOf(raw("1.0.1")))
        val store = FakeStore()
        val checker = checker(source, store)

        val first = checker.check(silent = true)
        clock += 5_000
        val second = checker.check(silent = true)

        assertThat(source.calls).isEqualTo(1)
        assertThat(second.status).isEqualTo(first.status)
        assertThat(second.release?.version).isEqualTo("1.0.1")
    }

    @Test
    fun `the manual check ignores the guard`() = runTest {
        val source = FakeSource(listOf(raw("1.0.1")))
        val checker = checker(source, FakeStore())

        checker.check(silent = true)
        clock += 1_000
        checker.check(silent = false, force = true)

        assertThat(source.calls).isEqualTo(2)
    }
}
