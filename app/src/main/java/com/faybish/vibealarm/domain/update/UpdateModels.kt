package com.faybish.vibealarm.domain.update

import kotlinx.serialization.Serializable

/** The APK asset naming contract, shared by the release script and the download page. */
object UpdateAssets {

    /**
     * The version-less copy every release must also carry. GitHub resolves
     * `/releases/latest/download/<name>` only for an exactly-named asset, and that
     * redirect is what lets the landing page's download button always hit the newest
     * build with no API call and no rate limit. `UpdateContractTest` pins this name
     * across the script and the page.
     */
    const val STABLE_APK = "vibealarm.apk"

    const val REPO = "devfassaf/vibrating_alarm_clock"

    /** The per-release name, preferred by [pickApkAsset] over the stable one. */
    fun versionedApk(tag: String): String = "vibealarm-${Versions.clean(tag)}.apk"

    fun latestDownloadUrl(): String =
        "https://github.com/$REPO/releases/latest/download/$STABLE_APK"

    fun releasesPageUrl(): String = "https://github.com/$REPO/releases"

    /**
     * Where the app sends someone who wants to know what this is: the project page, which
     * renders the README — what the app does, how to install it, and the download button.
     *
     * Deliberately **not** [pagesUrl]: `docs/index.html` is only served once the
     * repository owner switches GitHub Pages on, and until then that address answers 404 —
     * which is what a "home page" button must never do. The project page always resolves,
     * and its README links onward to the Pages site for whoever wants the prettier one.
     */
    fun siteUrl(): String = "https://github.com/$REPO#readme"

    /**
     * The landing page in docs/, once Pages is enabled (Settings → Pages → main, /docs).
     * Kept here because the release script and the README both point at it.
     */
    fun pagesUrl(): String {
        val (owner, repo) = REPO.split('/', limit = 2).let { it[0] to it[1] }
        return "https://$owner.github.io/$repo/"
    }

    fun releasesApiUrl(perPage: Int = 30): String =
        "https://api.github.com/repos/$REPO/releases?per_page=$perPage"
}

/** One downloadable file attached to a release. */
data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long)

/** A published release, reduced to what the updater needs. */
@Serializable
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val assetName: String,
    val assetUrl: String,
    val sizeBytes: Long,
    /** Parent-facing lines for this and every version the device would gain. */
    val whatsNew: List<VersionNotes> = emptyList(),
)

@Serializable
data class VersionNotes(val version: String, val lines: List<String>)

enum class UpdateStatus {
    /** Nothing newer is published. */
    UP_TO_DATE,

    /** Newer, and the user asked not to be reminded about this exact version. */
    SKIPPED,

    /** Newer and worth offering. */
    AVAILABLE,

    /** The check could not reach GitHub. Never surfaced as an error to the user. */
    UNAVAILABLE,
}

/** Outcome of one check, including the release when there is one. */
data class UpdateCheck(
    val status: UpdateStatus,
    val installedVersion: String?,
    val release: ReleaseInfo? = null,
)
