package com.faybish.vibealarm.domain.update

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

class UpdateDecisionsTest {

    private val now: Instant = Instant.parse("2026-08-12T22:00:00Z")

    private fun release(version: String) = ReleaseInfo(
        version = version,
        tag = "v$version",
        assetName = "vibealarm-v$version.apk",
        assetUrl = "https://example.test/vibealarm-v$version.apk",
        sizeBytes = 1_000,
    )

    // --- status ---

    @Test
    fun `a newer release is available`() {
        val status = UpdateDecisions.resolveStatus(release("1.0.1"), "1.0.0", null, silent = true)
        assertThat(status).isEqualTo(UpdateStatus.AVAILABLE)
    }

    @Test
    fun `the same or an older release is up to date`() {
        assertThat(UpdateDecisions.resolveStatus(release("1.0.0"), "1.0.0", null, true))
            .isEqualTo(UpdateStatus.UP_TO_DATE)
        assertThat(UpdateDecisions.resolveStatus(release("0.9.9"), "1.0.0", null, true))
            .isEqualTo(UpdateStatus.UP_TO_DATE)
        assertThat(UpdateDecisions.resolveStatus(null, "1.0.0", null, true))
            .isEqualTo(UpdateStatus.UP_TO_DATE)
    }

    @Test
    fun `a skipped version is hidden from the automatic check only`() {
        val silent = UpdateDecisions.resolveStatus(release("1.0.1"), "1.0.0", "1.0.1", silent = true)
        assertThat(silent).isEqualTo(UpdateStatus.SKIPPED)

        // A check the user asked for must surface it, or the button appears to do nothing.
        val manual = UpdateDecisions.resolveStatus(release("1.0.1"), "1.0.0", "1.0.1", silent = false)
        assertThat(manual).isEqualTo(UpdateStatus.AVAILABLE)
    }

    @Test
    fun `skipping one version does not hide the next one`() {
        val status = UpdateDecisions.resolveStatus(release("1.0.2"), "1.0.0", "1.0.1", silent = true)
        assertThat(status).isEqualTo(UpdateStatus.AVAILABLE)
    }

    @Test
    fun `a skipped version with an invisible mark still matches`() {
        val status = UpdateDecisions.resolveStatus(release("1.0.1"), "1.0.0", "‏1.0.1", silent = true)
        assertThat(status).isEqualTo(UpdateStatus.SKIPPED)
    }

    // --- the alarm-safety window ---

    @Test
    fun `an update is offered when no alarm is armed`() {
        assertThat(UpdateDecisions.shouldPrompt(UpdateStatus.AVAILABLE, now, nextAlarmAt = null)).isTrue()
    }

    @Test
    fun `an update is offered when the next alarm is comfortably away`() {
        val alarm = now.plus(Duration.ofHours(8))
        assertThat(UpdateDecisions.shouldPrompt(UpdateStatus.AVAILABLE, now, alarm)).isTrue()
    }

    /**
     * Installing replaces the app, and the system drops its scheduled alarms until
     * MY_PACKAGE_REPLACED puts them back. That recovery works, but it is not worth
     * running minutes before an alarm is due.
     */
    @Test
    fun `an update is withheld when an alarm is due inside the quiet window`() {
        listOf(Duration.ofMinutes(1), Duration.ofMinutes(29), Duration.ofMinutes(30)).forEach { away ->
            val alarm = now.plus(away)
            assertThat(UpdateDecisions.shouldPrompt(UpdateStatus.AVAILABLE, now, alarm)).isFalse()
            assertThat(UpdateDecisions.postponeReason(UpdateStatus.AVAILABLE, now, alarm))
                .isEqualTo(PostponeReason.ALARM_TOO_SOON)
        }
    }

    @Test
    fun `just past the quiet window the update is offered again`() {
        val alarm = now.plus(Duration.ofMinutes(31))
        assertThat(UpdateDecisions.shouldPrompt(UpdateStatus.AVAILABLE, now, alarm)).isTrue()
        assertThat(UpdateDecisions.postponeReason(UpdateStatus.AVAILABLE, now, alarm)).isNull()
    }

    @Test
    fun `an alarm in the past means a chain is mid-flight, so nothing is offered`() {
        val alarm = now.minus(Duration.ofSeconds(30))
        assertThat(UpdateDecisions.shouldPrompt(UpdateStatus.AVAILABLE, now, alarm)).isFalse()
    }

    @Test
    fun `nothing is ever prompted for a status other than available`() {
        listOf(UpdateStatus.UP_TO_DATE, UpdateStatus.SKIPPED, UpdateStatus.UNAVAILABLE).forEach {
            assertThat(UpdateDecisions.shouldPrompt(it, now, nextAlarmAt = null)).isFalse()
            assertThat(UpdateDecisions.postponeReason(it, now, nextAlarmAt = null)).isNull()
        }
    }

    // --- asset selection ---

    @Test
    fun `the versioned asset is preferred over the stable one`() {
        val assets = listOf(
            ReleaseAsset(UpdateAssets.STABLE_APK, "https://example.test/stable.apk", 10),
            ReleaseAsset("vibealarm-v1.0.1.apk", "https://example.test/versioned.apk", 20),
        )
        assertThat(UpdateDecisions.pickApkAsset(assets, "v1.0.1")?.name)
            .isEqualTo("vibealarm-v1.0.1.apk")
    }

    @Test
    fun `any apk is accepted when the expected name is absent`() {
        val assets = listOf(ReleaseAsset("something-else.apk", "https://example.test/x.apk", 5))
        assertThat(UpdateDecisions.pickApkAsset(assets, "v1.0.1")?.name).isEqualTo("something-else.apk")
    }

    @Test
    fun `a release with no apk yields nothing rather than a wrong file`() {
        val assets = listOf(ReleaseAsset("notes.txt", "https://example.test/notes.txt", 5))
        assertThat(UpdateDecisions.pickApkAsset(assets, "v1.0.1")).isNull()
        assertThat(UpdateDecisions.pickApkAsset(emptyList(), "v1.0.1")).isNull()
    }

    @Test
    fun `an invisible mark in the tag does not break asset matching`() {
        val assets = listOf(ReleaseAsset("vibealarm-v1.0.1.apk", "https://example.test/a.apk", 1))
        assertThat(UpdateDecisions.pickApkAsset(assets, "‏v1.0.1")?.name)
            .isEqualTo("vibealarm-v1.0.1.apk")
    }
}
