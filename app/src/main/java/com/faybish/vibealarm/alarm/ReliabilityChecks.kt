package com.faybish.vibealarm.alarm

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The health checks behind the Reliability screen.
 *
 * An alarm app fails in ways the user cannot see until the morning it matters, so
 * every requirement is turned into a check with a button that opens the exact
 * settings page that fixes it.
 */
enum class CheckId {
    EXACT_ALARMS,
    NOTIFICATIONS,
    FULL_SCREEN_INTENT,
    DRAW_OVER_OTHER_APPS,
    BATTERY_OPTIMIZATION,
    AMPLITUDE_CONTROL,
    SYSTEM_VIBRATION_STRENGTH,
    DND_TOTAL_SILENCE,
    OEM_BACKGROUND_LIMITS,
}

enum class CheckStatus {
    /** Requirement met. */
    OK,

    /** Broken and fixable — the alarm may not fire. */
    ACTION_NEEDED,

    /** Informational: nothing to fix, but worth knowing (e.g. hardware limits). */
    INFO,

    /** Cannot be verified programmatically; the user has to confirm manually. */
    MANUAL,
}

data class CheckResult(
    val id: CheckId,
    val status: CheckStatus,
    /** True when this check has a settings screen we can open. */
    val fixable: Boolean,
)

class ReliabilityChecks(
    private val context: Context,
    private val scheduler: AlarmScheduler,
) {

    fun runAll(): List<CheckResult> = listOf(
        exactAlarms(),
        notifications(),
        fullScreenIntent(),
        drawOverOtherApps(),
        batteryOptimization(),
        amplitudeControl(),
        systemVibrationStrength(),
        dndTotalSilence(),
        oemBackgroundLimits(),
    )

    private fun exactAlarms() = CheckResult(
        id = CheckId.EXACT_ALARMS,
        status = if (scheduler.canScheduleExactAlarms()) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
        fixable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
    )

    private fun notifications(): CheckResult {
        val enabled = context.getSystemService(NotificationManager::class.java)
            .areNotificationsEnabled()
        return CheckResult(
            id = CheckId.NOTIFICATIONS,
            status = if (enabled) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixable = true,
        )
    }

    private fun fullScreenIntent() = CheckResult(
        id = CheckId.FULL_SCREEN_INTENT,
        status = if (context.canUseFullScreenIntent()) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
        fixable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
    )

    /**
     * What makes the ringing screen appear on *every* ring rather than most of them.
     *
     * The full-screen intent above is a request; SystemUI weighs it only when the
     * notification is added and may decline without a word — measured on a dozing phone, it
     * granted the first and third rings of one chain and skipped the second, which leaves an
     * alarm vibrating with no snooze or dismiss on screen and the phone needing to be
     * unlocked. With this granted the alarm service starts that screen itself, which no
     * longer depends on the platform's mood.
     */
    private fun drawOverOtherApps() = CheckResult(
        id = CheckId.DRAW_OVER_OTHER_APPS,
        // INFO, not ACTION_NEEDED: the alarm rings and shows its screen without this. What
        // it buys is independence from the platform's judgement — with it granted the
        // service starts the screen itself instead of asking SystemUI to.
        status = if (Settings.canDrawOverlays(context)) CheckStatus.OK else CheckStatus.INFO,
        fixable = true,
    )

    private fun batteryOptimization(): CheckResult {
        val exempt = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
        return CheckResult(
            id = CheckId.BATTERY_OPTIMIZATION,
            status = if (exempt) CheckStatus.OK else CheckStatus.ACTION_NEEDED,
            fixable = true,
        )
    }

    /**
     * Not a failure: without amplitude control the app emulates intensity with
     * short pulses, which feels different but still works.
     */
    private fun amplitudeControl() = CheckResult(
        id = CheckId.AMPLITUDE_CONTROL,
        status = if (VibrationEngine(context).hasAmplitudeControl) CheckStatus.OK else CheckStatus.INFO,
        fixable = false,
    )

    /**
     * The app already asks the vibrator for its maximum (amplitude 255 is the hardware
     * ceiling, not a choice this app makes). The system's own vibration-intensity setting
     * scales everything on top of that, so if it is not at maximum the strongest pattern
     * still arrives weakened — and there is no API to read it, hence a manual pointer.
     */
    private fun systemVibrationStrength() = CheckResult(
        id = CheckId.SYSTEM_VIBRATION_STRENGTH,
        status = CheckStatus.MANUAL,
        fixable = true,
    )

    /**
     * Silent and vibrate-only modes never silence this app: sound goes out on the alarm
     * stream and vibration with alarm usage, and the ringer mode governs neither. Do Not
     * Disturb set to **total silence** is the one exception — it mutes alarms too, and
     * that is a real "my alarm did not go off" cause worth naming.
     *
     * Only the current filter is readable without notification-policy access, which is
     * enough: total silence is the setting that matters.
     */
    private fun dndTotalSilence(): CheckResult {
        val filter = context.getSystemService(NotificationManager::class.java)
            .currentInterruptionFilter
        val silenced = filter == NotificationManager.INTERRUPTION_FILTER_NONE
        return CheckResult(
            id = CheckId.DND_TOTAL_SILENCE,
            status = if (silenced) CheckStatus.ACTION_NEEDED else CheckStatus.OK,
            fixable = true,
        )
    }

    /**
     * Samsung's "put unused apps to sleep" (and its equivalents elsewhere) can
     * force-stop the app, which silently cancels every scheduled alarm. There is
     * no API to read that list, so this one is a guided manual step.
     */
    private fun oemBackgroundLimits() = CheckResult(
        id = CheckId.OEM_BACKGROUND_LIMITS,
        status = CheckStatus.MANUAL,
        fixable = true,
    )

    /** @return false when no settings screen could be opened for [id]. */
    fun openFix(id: CheckId): Boolean = when (id) {
        CheckId.EXACT_ALARMS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, appUri()))
            } else {
                false
            }

        CheckId.NOTIFICATIONS -> start(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )

        CheckId.FULL_SCREEN_INTENT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                start(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, appUri()))
            } else {
                false
            }

        CheckId.DRAW_OVER_OTHER_APPS ->
            start(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, appUri())) ||
                start(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))

        // Asking for the exemption directly is the point of this permission; the
        // generic battery-settings page is the fallback if the dialog is refused.
        CheckId.BATTERY_OPTIMIZATION ->
            start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri())) ||
                start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

        CheckId.AMPLITUDE_CONTROL -> false

        // The Zen settings action is documented but not exposed as a Settings constant, so
        // it is named here and falls back to the pages that definitely exist.
        CheckId.DND_TOTAL_SILENCE ->
            start(Intent(ACTION_ZEN_MODE_SETTINGS)) ||
                start(Intent(Settings.ACTION_SOUND_SETTINGS)) ||
                start(Intent(Settings.ACTION_SETTINGS))

        // No public intent lands on the vibration-intensity page itself; the sound
        // settings screen is one tap away from it on both stock Android and One UI.
        CheckId.SYSTEM_VIBRATION_STRENGTH ->
            start(Intent(Settings.ACTION_SOUND_SETTINGS)) ||
                start(Intent(Settings.ACTION_SETTINGS))

        // Device Care's activity names differ across One UI versions and are not
        // public API, so try the known ones and fall back to the app's own page.
        CheckId.OEM_BACKGROUND_LIMITS ->
            SAMSUNG_BATTERY_INTENTS.any { start(it()) } ||
                start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appUri()))
    }

    private fun appUri(): Uri = Uri.fromParts("package", context.packageName, null)

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private companion object {
        const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"

        val SAMSUNG_BATTERY_INTENTS: List<() -> Intent> = listOf(
            {
                Intent().setClassName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity",
                )
            },
            {
                Intent().setClassName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            },
            {
                Intent().setClassName(
                    "com.samsung.android.sm_cn",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            },
        )
    }
}
