package com.faybish.vibealarm

import android.content.Context
import com.faybish.vibealarm.alarm.AlarmNotifications
import com.faybish.vibealarm.alarm.AlarmScheduler
import com.faybish.vibealarm.alarm.SessionRuntime
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.SettingsStore
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

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDb by lazy { AppDb.build(deviceProtectedContext) }

    val repository: AlarmRepository by lazy { AlarmRepository(db) }

    val reliabilityLogger: ReliabilityLogger by lazy { ReliabilityLogger(db.logDao(), appScope) }

    val settings: SettingsStore by lazy { SettingsStore(deviceProtectedContext, appScope) }

    val notifications: AlarmNotifications by lazy { AlarmNotifications(deviceProtectedContext) }

    val scheduler: AlarmScheduler by lazy {
        AlarmScheduler(deviceProtectedContext, repository, reliabilityLogger)
    }

    val sessionRuntime: SessionRuntime by lazy {
        SessionRuntime(deviceProtectedContext, repository, scheduler, notifications, reliabilityLogger)
    }

    fun init(app: Context) {
        if (::deviceProtectedContext.isInitialized) return
        deviceProtectedContext = app.createDeviceProtectedStorageContext()
        notifications.ensureChannels()
        appScope.launch {
            repository.ensurePresetsSeeded()
            scheduler.armAll(sessionRuntime)
        }
    }
}
