package com.faybish.vibealarm.update

import android.content.Context
import com.faybish.vibealarm.domain.update.ReleaseInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DownloadResult {
    data class Ready(val apk: File) : DownloadResult
    data object Failed : DownloadResult

    /**
     * The file arrived incomplete. Reported separately because Android's own message for
     * installing a truncated APK is "there was a problem parsing the package", which
     * sends people looking for the wrong problem.
     */
    data object Truncated : DownloadResult
}

/**
 * Fetches the release APK into the app's own external files directory, which is the
 * only place both this app and the system installer can read (see file_paths.xml).
 */
class ApkDownloader(private val context: Context) {

    suspend fun download(
        release: ReleaseInfo,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), UPDATES_DIR).apply { mkdirs() }
        val target = File(dir, release.assetName)

        // A previous run may have already fetched exactly this file.
        if (target.isFile && release.sizeBytes > 0 && target.length() == release.sizeBytes) {
            return@withContext DownloadResult.Ready(target)
        }
        target.delete()

        val ok = runCatching { fetch(release, target, onProgress) }.getOrDefault(false)
        if (!ok) {
            target.delete()
            return@withContext DownloadResult.Failed
        }

        if (release.sizeBytes > 0 && target.length() != release.sizeBytes) {
            target.delete()
            return@withContext DownloadResult.Truncated
        }
        DownloadResult.Ready(target)
    }

    private fun fetch(
        release: ReleaseInfo,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val connection = (URL(release.assetUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true // the asset URL redirects to object storage
            setRequestProperty("User-Agent", "VibeAlarm")
        }
        try {
            if (connection.responseCode !in 200..299) return false
            val total = if (release.sizeBytes > 0) release.sizeBytes else connection.contentLengthLong
            var written = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }

    /** Removes a downloaded APK once the installer has finished with it. */
    fun deleteDownload(name: String) {
        runCatching { File(File(context.getExternalFilesDir(null), UPDATES_DIR), name).delete() }
    }

    companion object {
        /** Must match the external-files-path entry in res/xml/file_paths.xml. */
        const val UPDATES_DIR = "updates"

        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
    }
}
