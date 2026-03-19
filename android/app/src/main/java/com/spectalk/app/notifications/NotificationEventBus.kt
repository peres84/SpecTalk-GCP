package com.spectalk.app.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Singleton bus that carries notification tap events to the NavGraph.
 *
 * When a user taps a "Job complete" FCM notification, [FcmService] or
 * [MainActivity] emits the target conversationId here. The NavGraph collects
 * and navigates to VoiceSessionScreen for that conversation.
 */
object NotificationEventBus {
    private val _pendingConversationId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pendingConversationId = _pendingConversationId.asSharedFlow()

    fun emitConversationId(conversationId: String) {
        _pendingConversationId.tryEmit(conversationId)
    }
}
