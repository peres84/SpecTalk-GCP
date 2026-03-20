package com.spectalk.app.voice

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide command bus for voice session actions that can originate outside
 * the chat screen, such as FCM job-complete notifications.
 */
object VoiceSessionCommandBus {
    private val _resumeRequests = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val resumeRequests = _resumeRequests.asSharedFlow()

    fun requestResume(conversationId: String) {
        _resumeRequests.tryEmit(conversationId)
    }
}
