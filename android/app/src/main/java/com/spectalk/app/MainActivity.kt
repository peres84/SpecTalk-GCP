package com.spectalk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.spectalk.app.navigation.SpecTalkNavGraph
import com.spectalk.app.notifications.NotificationEventBus
import com.spectalk.app.ui.theme.SpecTalkTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra key carrying a conversation ID from an FCM notification tap. */
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle cold-start tap from a notification
        intent?.getStringExtra(EXTRA_CONVERSATION_ID)?.let { conversationId ->
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
            NotificationEventBus.emitConversationId(conversationId)
        }
    }
}
