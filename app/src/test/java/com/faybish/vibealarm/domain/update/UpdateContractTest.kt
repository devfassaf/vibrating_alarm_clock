package com.faybish.vibealarm.domain.update

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The distribution wiring, which is a coupling between three files that get edited at
 * completely different times for completely different reasons:
 *
 *   docs/index.html      the link a person clicks
 *   release.sh           what actually gets uploaded
 *   UpdateAssets         what the app looks for
 *
 * The mechanism is GitHub's `/releases/latest/download/<asset>` redirect, which matches
 * by EXACT asset name. Rename it in one place and the download button 404s silently —
 * nobody notices until someone reports a dead link, because nothing at runtime
 * exercises that URL. These tests are the only thing holding the three together.
 */
class UpdateContractTest {

    // Gradle runs unit tests with the module directory as the working directory.
    private val repoRoot = File("..").canonicalFile
    private val landingPage = File(repoRoot, "docs/index.html").readText()
    private val releaseScript = File(repoRoot, "release.sh").readText()
    private val gradleFile = File(repoRoot, "app/build.gradle.kts").readText()

    private val downloadHref: String? =
        Regex("""class="dl-btn"\s+href="([^"]+)"""").find(landingPage)?.groupValues?.get(1)

    @Test
    fun `the download button uses the latest-release redirect, never a fixed version`() {
        assertThat(downloadHref).isNotNull()
        assertThat(downloadHref).matches(
            """https://github\.com/[\w.-]+/[\w.-]+/releases/latest/download/[\w.-]+""",
        )
        // A version anywhere in the URL freezes the button on today's release.
        assertThat(Regex("""\d+\.\d+\.\d+""").containsMatchIn(downloadHref!!)).isFalse()
    }

    @Test
    fun `the download button points at the repo the app checks`() {
        assertThat(downloadHref).contains("/${UpdateAssets.REPO}/")
    }

    @Test
    fun `the button asks for exactly the asset the app names`() {
        val asset = downloadHref!!.substringAfterLast('/')
        assertThat(asset).isEqualTo(UpdateAssets.STABLE_APK)
    }

    @Test
    fun `the release script uploads the stable asset the button depends on`() {
        assertThat(releaseScript).contains(UpdateAssets.STABLE_APK)
        // Copied so the file exists, and passed to `gh release create` so the release
        // actually carries it. Uploading only the versioned name 404s the button.
        assertThat(releaseScript).contains("APK_LATEST")
        val ghLine = releaseScript.lineSequence()
            .filterNot { it.trimStart().startsWith("#") } // the comments mention it too
            .first { it.contains("gh release create") }
        assertThat(ghLine).contains("\"\$APK_LATEST\"")
        assertThat(ghLine).contains("\"\$APK\"")
    }

    @Test
    fun `the release script also uploads the versioned asset the updater prefers`() {
        // pickApkAsset prefers vibealarm-<tag>.apk and only falls back to "any apk";
        // dropping it would still work but silently demote the updater to the fallback.
        assertThat(releaseScript).contains("vibealarm-\$TAG.apk")
        assertThat(UpdateAssets.versionedApk("v1.2.3")).isEqualTo("vibealarm-v1.2.3.apk")
    }

    @Test
    fun `the release script publishes to the repo the app checks`() {
        assertThat(releaseScript).contains("REPO=\"${UpdateAssets.REPO}\"")
    }

    @Test
    fun `the release script refuses an undeliverable version`() {
        // The four-component trap. Without this guard a release can be published that
        // no installed app will ever offer — see VersionsTest.
        assertThat(releaseScript).contains("*.*.*.*")
    }

    @Test
    fun `the release script runs the tests before it builds`() {
        val testIndex = releaseScript.indexOf("testDebugUnitTest")
        val buildIndex = releaseScript.indexOf("assembleRelease")
        assertThat(testIndex).isGreaterThan(0)
        assertThat(buildIndex).isGreaterThan(testIndex)
    }

    @Test
    fun `a failed publish exits non-zero`() {
        // An unpublished release is a failed release: the build exists on one machine and
        // every device keeps answering "up to date". Exiting 0 there hides it.
        val tail = releaseScript.substringAfter("Could not publish")
        assertThat(tail).contains("die ")
    }

    /**
     * versionName and versionCode must move together. A new name with an unchanged code
     * installs as "the same build", so the update appears to do nothing at all.
     */
    @Test
    fun `the declared versionCode matches the formula applied to versionName`() {
        val name = Regex("""versionName\s*=\s*"([^"]+)"""").find(gradleFile)?.groupValues?.get(1)
        val code = Regex("""versionCode\s*=\s*(\d+)""").find(gradleFile)?.groupValues?.get(1)?.toInt()
        assertThat(name).isNotNull()
        assertThat(code).isNotNull()
        assertThat(Versions.isDeliverable(name)).isTrue()
        assertThat(code).isEqualTo(Versions.versionCode(name))
    }

    @Test
    fun `the release script derives the versionCode with the same formula as the app`() {
        assertThat(releaseScript).contains("major * 10000 + minor * 100 + patch")
    }

    /** The page the share message and the Settings button both point at must exist. */
    @Test
    fun `the site url matches the page this repo actually publishes`() {
        // GitHub Pages for a project repo serves at <owner>.github.io/<repo>/ from the
        // docs/ folder — which is where the landing page lives.
        assertThat(UpdateAssets.pagesUrl()).isEqualTo(
            "https://${UpdateAssets.REPO.substringBefore('/')}.github.io/" +
                "${UpdateAssets.REPO.substringAfter('/')}/",
        )
        assertThat(File(repoRoot, "docs/index.html").isFile).isTrue()
    }

    @Test
    fun `the landing page explains the Samsung background limit`() {
        // The one setting that silently cancels every scheduled alarm, and the one thing
        // a new user must be told before they rely on this app.
        assertThat(landingPage).contains("אפליקציות ישנות")
    }
}
