package com.spectalk.app.voice

data class VoiceSessionUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isMicStreaming: Boolean = false,
    val statusMessage: String = "Disconnected",
    val latestUserTranscript: String = "",
    val latestAssistantTranscript: String = "",
    val activeJobDescription: String = "",
    val recentError: String? = null,
    val isBtHeadsetConnected: Boolean = false,
)
