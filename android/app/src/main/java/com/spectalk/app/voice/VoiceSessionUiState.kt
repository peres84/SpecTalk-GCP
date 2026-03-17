package com.spectalk.app.voice

data class ConversationTurn(val role: String, val text: String)

data class VoiceSessionUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isMicStreaming: Boolean = false,
    val statusMessage: String = "Disconnected",
    val turns: List<ConversationTurn> = emptyList(),
    val activeJobDescription: String = "",
    val recentError: String? = null,
    val isBtHeadsetConnected: Boolean = false,
)
