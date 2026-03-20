package com.spectalk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.spectalk.app.device.MetaWearablesPermissionBridge
import com.spectalk.app.navigation.SpecTalkNavGraph
import com.spectalk.app.notifications.NotificationEventBus
import com.spectalk.app.settings.AppPreferences
import com.spectalk.app.ui.theme.SpecTalkTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra key carrying a conversation ID from an FCM notification tap. */
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }

    private var wearablesPermissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private val wearablesPermissionMutex = Mutex()

    private val wearablesPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
            wearablesPermissionContinuation?.resume(permissionStatus)
            wearablesPermissionContinuation = null
        }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
        return wearablesPermissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                wearablesPermissionContinuation = continuation
                continuation.invokeOnCancellation { wearablesPermissionContinuation = null }
                wearablesPermissionLauncher.launch(permission)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MetaWearablesPermissionBridge.install(::requestWearablesPermission)

        // Handle cold-start tap from a notification
        intent?.getStringExtra(EXTRA_CONVERSATION_ID)?.let { conversationId ->
            AppPreferences.setPendingAutoOpenConversationId(this, conversationId)
            NotificationEventBus.emitConversationId(conversationId)
        }

        setContent {
            SpecTalkTheme {
                SpecTalkNavGraph()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle warm-start tap from a notification (app already running)
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { conversationId ->
            AppPreferences.setPendingAutoOpenConversationId(this, conversationId)
            NotificationEventBus.emitConversationId(conversationId)
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            MetaWearablesPermissionBridge.clear()
        }
        super.onDestroy()
    }
}
