package com.faybish.vibealarm

import android.content.Context

/**
 * Manual service locator. The only [Context] it ever exposes to the data and
 * alarm layers is the device-protected one, so nothing on the alarm-critical
 * path can accidentally depend on credential-encrypted storage (which is
 * unavailable after a reboot until the user unlocks the phone).
 */
object AppGraph {

    lateinit var deviceProtectedContext: Context
        private set

    fun init(app: Context) {
        if (::deviceProtectedContext.isInitialized) return
        deviceProtectedContext = app.createDeviceProtectedStorageContext()
    }
}
