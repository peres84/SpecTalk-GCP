package com.spectalk.app.hotword

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object HotwordServiceStarter {
    fun startIfPermitted(context: Context) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        HotwordEventBus.resume()
        runCatching {
            context.startForegroundService(Intent(context, HotwordService::class.java))
        }
    }
}
