package com.faybish.vibealarm

import android.app.Application

/**
 * IMPORTANT (Direct Boot): this class may be instantiated Before First Unlock.
 * Nothing here may touch credential-encrypted storage — all app state lives in
 * Device Protected Storage via [AppGraph].
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
