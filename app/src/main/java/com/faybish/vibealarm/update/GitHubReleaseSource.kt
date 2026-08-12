package com.faybish.vibealarm.update

import com.faybish.vibealarm.domain.update.RawRelease
import com.faybish.vibealarm.domain.update.ReleaseAsset
import com.faybish.vibealarm.domain.update.ReleaseSource
import com.faybish.vibealarm.domain.update.UpdateAssets
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the release list from GitHub's public API.
 *
 * One request returns both the newest release and the notes of every version in
 * between, so a device that skipped several versions can be shown everything it
 * missed. Unauthenticated, which is why the caller checks at most once a minute.
 *
 * Every failure — offline, rate limit, malformed JSON, a hang — returns null. The
 * updater is a convenience; it must never turn into an error the user has to dismiss.
 */
class GitHubReleaseSource(
    private val apiUrl: String = UpdateAssets.releasesApiUrl(),
) : ReleaseSource {

    override suspend fun fetchReleases(): List<RawRelease>? = withContext(Dispatchers.IO) {
        val body = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { get(apiUrl) } ?: return@withContext null
        runCatching { json.decodeFromString<List<GitHubRelease>>(body).map { it.toRawRelease() } }
            .getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VibeAlarm")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 8_000

        /** Outer bound: a socket that neither fails nor answers must not hang the check. */
        const val REQUEST_TIMEOUT_MILLIS = 12_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    @SerialName("prerelease") val preRelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    fun toRawRelease() = RawRelease(
        tag = tagName,
        body = body,
        draft = draft,
        preRelease = preRelease,
        assets = assets.map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size) },
    )
}

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)
