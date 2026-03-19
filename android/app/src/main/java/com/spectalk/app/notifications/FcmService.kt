package com.spectalk.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.spectalk.app.MainActivity
import com.spectalk.app.R
import com.spectalk.app.SpecTalkApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * FCM message handler. Receives push notifications from the backend.
 *
 * Notification data payload shape (from job_service.py / internal/jobs.py):
 *   conversation_id : String  — the target conversation
 *   job_id          : String  — the completed job
 *   event_type      : String  — "job_completed" | "job_failed"
 *   resume_event_id : String  — resume event to ack after welcome-back
 *
 * The notification/title and notification/body are set by the backend.
 * If only a data payload is sent (no notification block), we fall back to
 * the data["title"] and data["body"] fields.
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

        val data = message.data
        val conversationId = data["conversation_id"] ?: run {
            Log.w(TAG, "FCM message missing conversation_id — ignoring")
            return
        }

        val title = message.notification?.title
            ?: data["title"]
            ?: "SpecTalk — Job Complete"
        val body = message.notification?.body
            ?: data["body"]
            ?: data["display_summary"]
            ?: "Your job has finished. Tap to continue."

        showJobNotification(conversationId, title, body)
    }

    private fun showJobNotification(conversationId: String, title: String, body: String) {
        // Intent that opens MainActivity with the target conversation
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_RESUME)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        // Use conversation hashCode as notification ID so updates replace previous ones
        manager.notify(conversationId.hashCode(), notification)
    }
}
