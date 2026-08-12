package com.faybish.vibealarm.ui.settings

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.faybish.vibealarm.domain.update.ShareMessage
import com.faybish.vibealarm.domain.update.UpdateAssets

/**
 * The two outward-facing actions in Settings: open the app's page, and pass the app on.
 *
 * Both are best-effort — a device with no browser or no app that accepts text should not
 * crash Settings — so each reports whether it got anywhere.
 */
object SettingsActions {

    /** Opens the explanation and download page in whatever browser the device has. */
    fun openSite(context: Context): Boolean = open(context, UpdateAssets.siteUrl())

    fun openReleases(context: Context): Boolean = open(context, UpdateAssets.releasesPageUrl())

    /**
     * Hands the share text to the system chooser.
     *
     * @param version the installed version, named in the message so the recipient knows
     *   what they are getting.
     */
    fun shareApp(context: Context, version: String?, subject: String): Boolean {
        val text = ShareMessage.build(
            version = version,
            siteUrl = UpdateAssets.siteUrl(),
            apkUrl = UpdateAssets.latestDownloadUrl(),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser); true }.getOrDefault(false)
    }

    private fun open(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
