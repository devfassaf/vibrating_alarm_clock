package com.faybish.vibealarm.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

sealed interface InstallResult {
    data object Started : InstallResult

    /** The user has not allowed this app to install packages. */
    data object PermissionMissing : InstallResult

    /** file_paths.xml does not cover the download directory — the classic misconfiguration. */
    data object ProviderNotConfigured : InstallResult

    data object NoInstaller : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Two things are not negotiable here: the URI must come from our own FileProvider (a
 * raw file:// URI throws FileUriExposedException on API 24+), and the receiving
 * installer needs read permission on it or it reports a corrupt package.
 *
 * The downloaded APK must be signed with the same key as the installed app, or Android
 * refuses the install outright. That is a release-process guarantee (one keystore, kept
 * safe), not something this code can repair at runtime.
 */
class ApkInstaller(private val context: Context) {

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(apk: File): InstallResult {
        if (!apk.isFile) return InstallResult.Failed("file-missing")
        if (!canInstallPackages()) return InstallResult.PermissionMissing

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            // ACTION_INSTALL_PACKAGE is deprecated since API 29; ACTION_VIEW on the
            // package-archive type is the supported route.
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallResult.Started
        } catch (e: IllegalArgumentException) {
            InstallResult.ProviderNotConfigured
        } catch (e: ActivityNotFoundException) {
            InstallResult.NoInstaller
        } catch (e: Exception) {
            InstallResult.Failed(e.message)
        }
    }

    /** Opens the system page where "install unknown apps" is granted for this app. */
    fun openInstallPermissionSettings(): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** The version this code is running as — the baseline every comparison uses. */
    fun installedVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()
}
