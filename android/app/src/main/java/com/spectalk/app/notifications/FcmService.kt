package com.spectalk.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.spectalk.app.SpecTalkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM message handler. Receives push notifications from the backend.
 *
 * Phase 1: stub — just logs the token and message.
 * Phase 4: implement job-complete and resume-event notifications.
 */
class FcmService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "FcmService"

        const val CHANNEL_ID_JOBS = "spectalk_jobs"
        const val CHANNEL_ID_RESUME = "spectalk_resume"

        fun createNotificationChannels(manager: NotificationManager) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_JOBS,
                    "Job Updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Background job progress and completion" }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_RESUME,
                    "Conversation Resume",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Tap to continue a conversation with Gervis" }
            )
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed: ${token.take(20)}…")
        val tokenRepository = (application as SpecTalkApplication).tokenRepository
        serviceScope.launch {
            runCatching { tokenRepository.registerPushToken(token) }
                .onFailure { e -> Log.w(TAG, "Push token registration failed: ${e.message}") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d(TAG, "FCM message received: ${message.data}")
        // Phase 4: parse message.data["type"] and show job/resume notification
    }
}
