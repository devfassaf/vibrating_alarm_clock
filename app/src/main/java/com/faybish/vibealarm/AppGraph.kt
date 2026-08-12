package com.faybish.vibealarm

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.faybish.vibealarm.alarm.AlarmNotifications
import com.faybish.vibealarm.alarm.AlarmScheduler
import com.faybish.vibealarm.alarm.SessionRuntime
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual service locator.
 *
 * The only [Context] it exposes to the data and alarm layers is the
 * device-protected one, so nothing on the alarm-critical path can accidentally
 * depend on credential-encrypted storage — which is unavailable after a reboot
 * until the user unlocks the phone, the exact scenario this app must survive.
 */
object AppGraph {

    lateinit var deviceProtectedContext: Context
        private set

    /**
     * SupervisorJob keeps one failure from cancelling siblings, but it does not stop
     * an uncaught exception from reaching the default handler and killing the process
     * mid-alarm — hence the handler.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error ->
                Log.e(ReliabilityLogger.TAG, "unhandled error on the alarm path", error)
                runCatching {
                    reliabilityLogger.log(
                        ReliabilityLogger.EFFECT_FAILED,
                        "${error.javaClass.simpleName}: ${error.message}",
                    )
                }
            },
    )

    private var dbOrNull: AppDb? = null
    private var repositoryOrNull: AlarmRepository? = null
    private var loggerOrNull: ReliabilityLogger? = null
    private var settingsOrNull: SettingsStore? = null
    private var notificationsOrNull: AlarmNotifications? = null
    private var schedulerOrNull: AlarmScheduler? = null
    private var runtimeOrNull: SessionRuntime? = null

    val db: AppDb
        get() = dbOrNull ?: AppDb.build(deviceProtectedContext).also { dbOrNull = it }

    val repository: AlarmRepository
        get() = repositoryOrNull ?: AlarmRepository(db).also { repositoryOrNull = it }

    val reliabilityLogger: ReliabilityLogger
        get() = loggerOrNull ?: ReliabilityLogger(db.logDao(), appScope).also { loggerOrNull = it }

    val settings: SettingsStore
        get() = settingsOrNull
            ?: SettingsStore(deviceProtectedContext, appScope).also { settingsOrNull = it }

    val notifications: AlarmNotifications
        get() = notificationsOrNull
            ?: AlarmNotifications(deviceProtectedContext).also { notificationsOrNull = it }

    val scheduler: AlarmScheduler
        get() = schedulerOrNull
            ?: AlarmScheduler(deviceProtectedContext, repository, reliabilityLogger)
                .also { schedulerOrNull = it }

    val sessionRuntime: SessionRuntime
        get() = runtimeOrNull ?: SessionRuntime(
            deviceProtectedContext,
            repository,
            scheduler,
            notifications,
            reliabilityLogger,
        ).also { runtimeOrNull = it }

    /**
     * Called from [App.onCreate], which runs before any component in the process —
     * including the alarm receiver on a cold trigger. It deliberately does NOT re-arm
     * the schedule: doing that on every process start would race the very trigger the
     * process was woken for, and a Resume landing on a live ring can demote it back to
     * snoozed. Re-arming belongs to the boot and system-event receivers and to
     * [syncSchedule], which the UI calls on start.
     */
    fun init(app: Context) {
        if (::deviceProtectedContext.isInitialized) return
        deviceProtectedContext = app.createDeviceProtectedStorageContext()
        notifications.ensureChannels()
        appScope.launch { repository.ensurePresetsSeeded() }
    }

    /** Re-derives the whole schedule from persisted state. Safe to call repeatedly. */
    fun syncSchedule() {
        appScope.launch { scheduler.armAll(sessionRuntime) }
    }

    /**
     * Rebinds the graph to a fresh context and drops every cached component, so a test
     * class does not inherit the previous one's database. Production code calls [init].
     */
    @VisibleForTesting
    fun resetForTests(app: Context) {
        dbOrNull?.close()
        dbOrNull = null
        repositoryOrNull = null
        loggerOrNull = null
        settingsOrNull = null
        notificationsOrNull = null
        schedulerOrNull = null
        runtimeOrNull = null
        deviceProtectedContext = app.createDeviceProtectedStorageContext()
        notifications.ensureChannels()
    }
}
