package com.faybish.vibealarm

import android.content.Context
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.ReliabilityLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual service locator. The only [Context] it ever exposes to the data and
 * alarm layers is the device-protected one, so nothing on the alarm-critical
 * path can accidentally depend on credential-encrypted storage (which is
 * unavailable after a reboot until the user unlocks the phone).
 */
object AppGraph {

    lateinit var deviceProtectedContext: Context
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDb by lazy { AppDb.build(deviceProtectedContext) }

    val repository: AlarmRepository by lazy { AlarmRepository(db) }

    val reliabilityLogger: ReliabilityLogger by lazy { ReliabilityLogger(db.logDao(), appScope) }

    fun init(app: Context) {
        if (::deviceProtectedContext.isInitialized) return
        deviceProtectedContext = app.createDeviceProtectedStorageContext()
        appScope.launch { repository.ensurePresetsSeeded() }
    }
}
