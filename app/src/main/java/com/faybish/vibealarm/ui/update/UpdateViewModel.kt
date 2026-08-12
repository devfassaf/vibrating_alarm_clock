package com.faybish.vibealarm.ui.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.domain.NextOccurrenceCalculator
import com.faybish.vibealarm.domain.update.PostponeReason
import com.faybish.vibealarm.domain.update.ReleaseInfo
import com.faybish.vibealarm.domain.update.UpdateChecker
import com.faybish.vibealarm.domain.update.UpdateDecisions
import com.faybish.vibealarm.domain.update.UpdateStatus
import com.faybish.vibealarm.update.ApkDownloader
import com.faybish.vibealarm.update.ApkInstaller
import com.faybish.vibealarm.update.DownloadResult
import com.faybish.vibealarm.update.InstallResult
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the update surface is currently doing. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    /** A newer release, and the user has not been asked about it yet. */
    data class Available(val release: ReleaseInfo) : UpdateUiState

    data class Downloading(val release: ReleaseInfo, val bytes: Long, val total: Long) : UpdateUiState

    /** The installer was launched; Android owns the screen from here. */
    data object Installing : UpdateUiState

    data class Problem(val kind: ProblemKind) : UpdateUiState

    /** Newer version exists but now is the wrong moment (an alarm is close). */
    data class Postponed(val release: ReleaseInfo, val reason: PostponeReason) : UpdateUiState

    /** Result of a check the user asked for, when there is nothing to install. */
    data object AlreadyCurrent : UpdateUiState
}

enum class ProblemKind { NO_NETWORK, DOWNLOAD_FAILED, TRUNCATED, INSTALL_PERMISSION, INSTALL_FAILED }

/**
 * Drives the update check that runs on every app open, and the download/install the user
 * agrees to.
 *
 * Nothing here can delay startup: the check is launched into the view model's scope and
 * the UI renders regardless of how long GitHub takes (or whether it answers at all).
 */
class UpdateViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val installer = ApkInstaller(appContext)
    private val downloader = ApkDownloader(appContext)

    private val checker = UpdateChecker(
        source = AppGraph.releaseSource,
        store = AppGraph.updateStore,
        installedVersion = { installer.installedVersion() },
    )

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val installedVersion: String? = installer.installedVersion()

    /**
     * The automatic check. Silent in the sense that it only ever *opens* a dialog: a
     * failure, a skipped version, or an alarm due shortly all leave the screen alone.
     */
    fun checkOnOpen() {
        viewModelScope.launch {
            val result = runCatching { checker.check(silent = true) }.getOrNull()
            if (result == null) {
                log("failed")
                return@launch
            }
            val release = result.release
            if (result.status != UpdateStatus.AVAILABLE || release == null) {
                log("${result.status} installed=${result.installedVersion}")
                return@launch
            }

            val now = Instant.now()
            val prompt = UpdateDecisions.shouldPrompt(result.status, now, nextAlarmAt())
            // A check that runs on every open is invisible by design, so its outcome goes
            // to the reliability log — otherwise "nothing happened" and "it silently
            // failed every time" look exactly the same.
            log("available=${release.version} prompted=$prompt")
            if (prompt) _state.value = UpdateUiState.Available(release)
        }
    }

    private fun log(detail: String) =
        AppGraph.reliabilityLogger.log(ReliabilityLogger.UPDATE_CHECK, detail)

    /** The explicit "check for update" button: always reports something back. */
    fun checkNow() {
        viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            val result = runCatching { checker.check(silent = false, force = true) }.getOrNull()
            val release = result?.release
            _state.value = when {
                result == null || result.status == UpdateStatus.UNAVAILABLE ->
                    UpdateUiState.Problem(ProblemKind.NO_NETWORK)

                result.status != UpdateStatus.AVAILABLE || release == null ->
                    UpdateUiState.AlreadyCurrent

                else -> {
                    val now = Instant.now()
                    val reason = UpdateDecisions.postponeReason(result.status, now, nextAlarmAt())
                    if (reason == null) {
                        UpdateUiState.Available(release)
                    } else {
                        UpdateUiState.Postponed(release, reason)
                    }
                }
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateUiState.Idle
    }

    fun skip(release: ReleaseInfo) {
        viewModelScope.launch {
            checker.skip(release.version)
            _state.value = UpdateUiState.Idle
        }
    }

    fun download(release: ReleaseInfo) {
        viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(release, 0, release.sizeBytes)
            val result = downloader.download(release) { bytes, total ->
                _state.update { current ->
                    if (current is UpdateUiState.Downloading) current.copy(bytes = bytes, total = total) else current
                }
            }
            _state.value = when (result) {
                is DownloadResult.Failed -> UpdateUiState.Problem(ProblemKind.DOWNLOAD_FAILED)
                is DownloadResult.Truncated -> UpdateUiState.Problem(ProblemKind.TRUNCATED)
                is DownloadResult.Ready -> when (val install = installer.install(result.apk)) {
                    InstallResult.Started -> {
                        AppGraph.reliabilityLogger.log(
                            ReliabilityLogger.UPDATE_INSTALL,
                            "installer launched for ${release.version}",
                        )
                        UpdateUiState.Installing
                    }
                    InstallResult.PermissionMissing -> UpdateUiState.Problem(ProblemKind.INSTALL_PERMISSION)
                    else -> {
                        AppGraph.reliabilityLogger.log(
                            ReliabilityLogger.UPDATE_INSTALL,
                            "failed: $install",
                        )
                        UpdateUiState.Problem(ProblemKind.INSTALL_FAILED)
                    }
                }
            }
        }
    }

    fun openInstallPermissionSettings() {
        installer.openInstallPermissionSettings()
    }

    /** The soonest armed alarm, so an update is never offered right before one. */
    private suspend fun nextAlarmAt(): Instant? {
        val repository = AppGraph.repository
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        return repository.getEnabledAlarms()
            .mapNotNull { alarm ->
                repository.activeInstance(alarm.id)?.let { Instant.ofEpochMilli(it.nextActionEpochMillis) }
                    ?: NextOccurrenceCalculator.nextTrigger(repository.scheduleOf(alarm), now, zone)
            }
            .minOrNull()
    }
}
