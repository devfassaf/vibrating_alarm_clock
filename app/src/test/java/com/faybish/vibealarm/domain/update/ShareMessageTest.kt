package com.faybish.vibealarm.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareMessageTest {

    private fun message(version: String? = "1.0.1") = ShareMessage.build(
        version = version,
        siteUrl = UpdateAssets.siteUrl(),
        apkUrl = UpdateAssets.latestDownloadUrl(),
    )

    @Test
    fun `the message carries both the page and the direct download`() {
        val text = message()
        assertThat(text).contains(UpdateAssets.siteUrl())
        assertThat(text).contains(UpdateAssets.latestDownloadUrl())
    }

    /**
     * A shared message sits in a chat thread for months. A versioned asset URL would keep
     * handing out a stale build, and 404 outright once that release is deleted — so the
     * link has to be the latest-release redirect.
     */
    @Test
    fun `the download link is the latest-release redirect, not a pinned version`() {
        val text = message()
        assertThat(text).contains("/releases/latest/download/")
        // The version appears as prose, never inside a URL.
        val urls = Regex("""https?://\S+""").findAll(text).map { it.value }.toList()
        assertThat(urls).isNotEmpty()
        urls.forEach { assertThat(Regex("""\d+\.\d+\.\d+""").containsMatchIn(it)).isFalse() }
    }

    @Test
    fun `the page is mentioned before the apk`() {
        // A bare .apk link with no context reads as something you should not tap, and
        // leads straight into Android's warnings with nobody having explained them.
        val text = message()
        assertThat(text.indexOf(UpdateAssets.siteUrl()))
            .isLessThan(text.indexOf(UpdateAssets.latestDownloadUrl()))
    }

    @Test
    fun `the installed version is named when known`() {
        assertThat(message("1.0.1")).contains("1.0.1")
    }

    @Test
    fun `an unknown version leaves no empty brackets behind`() {
        listOf(null, "", "   ").forEach { version ->
            val text = message(version)
            assertThat(text).doesNotContain("()")
            assertThat(text).doesNotContain("גירסה )")
            assertThat(text).contains(UpdateAssets.siteUrl())
        }
    }

    @Test
    fun `it warns that Android will show install warnings`() {
        // Whoever receives this walks into Chrome's warning, the unknown-sources prompt
        // and a Play Protect scan. Saying so up front is the whole point of the message.
        assertThat(message()).contains("אזהרות")
    }

    @Test
    fun `the site url is derived from the repo, so the two cannot drift`() {
        val (owner, repo) = UpdateAssets.REPO.split('/')
        // The landing page, served by Pages from main /docs.
        assertThat(UpdateAssets.siteUrl()).isEqualTo("https://$owner.github.io/$repo/")
        // The address that resolves no matter how the repository is configured.
        assertThat(UpdateAssets.projectUrl()).isEqualTo("https://github.com/$owner/$repo")
    }
}
