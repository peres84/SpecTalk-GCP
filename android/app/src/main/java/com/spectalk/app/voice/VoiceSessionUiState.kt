package com.spectalk.app.voice

data class ConversationTurn(
    val role: String,
    val text: String,
    val kind: String = "message",
    val imagePath: String? = null,
    val activityId: String? = null,
    val activityStatus: String? = null,
    val activityDurationMs: Long? = null,
)

data class VoiceSessionUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isMicStreaming: Boolean = false,
    val isListeningEnabled: Boolean = true,
    val isAudioPlaybackEnabled: Boolean = true,
    val statusMessage: String = "Disconnected",
    val turns: List<ConversationTurn> = emptyList(),
    val draftText: String = "",
    val activeJobDescription: String = "",
    val recentError: String? = null,
    val isWakeWordDeviceConnected: Boolean = false,
    /** The conversation ID currently in use. Set once the session resolves a conversation. */
    val conversationId: String? = null,
    /**
     * Current backend conversation state from the last state_update control message.
     * Key values: "idle", "active", "coding_mode", "awaiting_confirmation", "running_job".
     * Persisted across reconnects via SharedPreferences so the PRD card can be restored.
     */
    val conversationState: String = "",
    /**
     * PRD summary received via the awaiting_confirmation state_update.
     * Non-null only while the conversation is in awaiting_confirmation state.
     * Cleared on running_job or idle state transitions.
     */
    val prdSummary: PrdSummary? = null,
    /** True when the Meta glasses StreamSession is STREAMING and a photo can be captured. */
    val isGlassesCameraReady: Boolean = false,
)
