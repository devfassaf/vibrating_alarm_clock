package com.faybish.vibealarm.domain.update

import java.time.Duration
import java.time.Instant

/**
 * The rules that decide what a check means and whether to act on it. Pure, so the
 * behaviour is pinned by tests rather than discovered on a phone at 6am.
 */
object UpdateDecisions {

    /**
     * Installing an update stops the app, and the system cancels its scheduled alarms
     * for the duration; they come back through MY_PACKAGE_REPLACED. That recovery is
     * reliable, but it is not worth exercising minutes before an alarm is due — so an
     * update is simply not offered inside this window, and the prompt appears the next
     * time the app is opened.
     */
    val ALARM_QUIET_WINDOW: Duration = Duration.ofMinutes(30)

    /**
     * @param silent true for the automatic check on app open. A check the user asked
     *   for must surface the update even if they previously skipped that version —
     *   otherwise the button appears to do nothing.
     */
    fun resolveStatus(
        release: ReleaseInfo?,
        installedVersion: String?,
        skippedVersion: String?,
        silent: Boolean,
    ): UpdateStatus = when {
        release == null || installedVersion == null -> UpdateStatus.UP_TO_DATE
        !Versions.isNewer(release.version, installedVersion) -> UpdateStatus.UP_TO_DATE
        silent && skippedVersion != null && Versions.clean(skippedVersion) == Versions.clean(release.version) ->
            UpdateStatus.SKIPPED

        else -> UpdateStatus.AVAILABLE
    }

    /**
     * Whether to actually put the prompt on screen.
     *
     * @param nextAlarmAt the soonest armed alarm, or null when nothing is armed.
     */
    fun shouldPrompt(
        status: UpdateStatus,
        now: Instant,
        nextAlarmAt: Instant?,
        quietWindow: Duration = ALARM_QUIET_WINDOW,
    ): Boolean {
        if (status != UpdateStatus.AVAILABLE) return false
        val alarm = nextAlarmAt ?: return true
        // An alarm already in the past is a chain mid-flight or a stale row; either way
        // this is not the moment to replace the app.
        if (alarm.isBefore(now)) return false
        return Duration.between(now, alarm) > quietWindow
    }

    /** Why the prompt is being withheld, for the "check for update" button's feedback. */
    fun postponeReason(
        status: UpdateStatus,
        now: Instant,
        nextAlarmAt: Instant?,
        quietWindow: Duration = ALARM_QUIET_WINDOW,
    ): PostponeReason? = when {
        status != UpdateStatus.AVAILABLE -> null
        shouldPrompt(status, now, nextAlarmAt, quietWindow) -> null
        else -> PostponeReason.ALARM_TOO_SOON
    }

    /** Prefer the exact per-release asset; fall back to any APK so a release is never unusable. */
    fun pickApkAsset(assets: List<ReleaseAsset>, tag: String): ReleaseAsset? {
        val wanted = UpdateAssets.versionedApk(tag)
        return assets.firstOrNull { it.name == wanted }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }
}

enum class PostponeReason { ALARM_TOO_SOON }
