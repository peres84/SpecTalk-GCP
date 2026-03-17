package com.spectalk.app

import android.app.Application
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.spectalk.app.auth.TokenRepository
import com.spectalk.app.config.BackendConfig
import com.spectalk.app.notifications.FcmService

class SpecTalkApplication : Application() {

    val tokenRepository: TokenRepository by lazy { TokenRepository(this) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        BackendConfig.init(getString(R.string.backend_base_url))
        val nm = getSystemService(NotificationManager::class.java)
        FcmService.createNotificationChannels(nm)
    }
}
