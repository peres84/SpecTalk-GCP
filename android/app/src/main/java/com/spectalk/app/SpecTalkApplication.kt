package com.spectalk.app

import android.app.Application
import com.google.firebase.FirebaseApp

class SpecTalkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
