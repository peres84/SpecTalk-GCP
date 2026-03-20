package com.spectalk.app

import android.app.Application
import android.app.NotificationManager
import android.util.Log
import com.google.firebase.FirebaseApp
import com.meta.wearable.dat.core.Wearables
import com.spectalk.app.auth.TokenRepository
import com.spectalk.app.config.BackendConfig
import com.spectalk.app.device.ConnectedDeviceMonitor
import com.spectalk.app.hotword.HotwordServiceStarter
import com.spectalk.app.location.UserLocationRepository
import com.spectalk.app.notifications.FcmService

class SpecTalkApplication : Application() {
    companion object {
        private const val TAG = "SpecTalkApplication"
    }

    val tokenRepository: TokenRepository by lazy { TokenRepository(this) }
    val userLocationRepository: UserLocationRepository by lazy { UserLocationRepository(this) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        BackendConfig.init(getString(R.string.backend_base_url))
        runCatching { Wearables.initialize(this) }
            .onFailure { e -> Log.w(TAG, "Wearables.initialize failed: ${e.message}") }
        ConnectedDeviceMonitor.start(this)
        HotwordServiceStarter.startIfPermitted(this)
        val nm = getSystemService(NotificationManager::class.java)
        FcmService.createNotificationChannels(nm)
    }
}
