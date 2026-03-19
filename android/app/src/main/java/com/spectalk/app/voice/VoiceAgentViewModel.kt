package com.spectalk.app.voice

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectalk.app.R
import com.spectalk.app.SpecTalkApplication
import com.spectalk.app.audio.AndroidAudioRecorder
import com.spectalk.app.audio.PcmAudioPlayer
import com.spectalk.app.config.BackendConfig
import com.spectalk.app.conversations.ConversationRepository
import com.spectalk.app.device.ConnectedDeviceMonitor
import com.spectalk.app.hotword.HotwordEventBus
import com.spectalk.app.location.UserLocationContext
import com.spectalk.app.settings.AppPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VoiceAgentViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VoiceAgentViewModel"
        private const val INACTIVITY_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    private val app = application as SpecTalkApplication
    private val tokenRepository = app.tokenRepository
    private val userLocationRepository = app.userLocationRepository
    private val conversationRepository = ConversationRepository()

    private val _uiState = MutableStateFlow(VoiceSessionUiState())
    val uiState: StateFlow<VoiceSessionUiState> = _uiState.asStateFlow()

    private var backendClient: BackendVoiceClient? = null
    private var clientEventsJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var inactivityJob: Job? = null

    // Tracks whether we've sent ack-resume-event for the current session.
    // Reset on each new session so the ack fires exactly once per reconnect.
    @Volatile private var resumeEventAcked = false

    private var audioRecorder: AndroidAudioRecorder? = null
    private var audioPlayer: PcmAudioPlayer? = null

    private var soundPool: SoundPool? = null
    private var activationSoundId: Int = 0

    // Updated on every outgoing audio chunk. The inactivity timer checks this so it
    // doesn't fire while the user is actively speaking (before a transcript arrives).
    @Volatile private var lastAudioSentTime: Long = 0L

    init {
        initActivationSound()
        observeConnectedDevices()

        viewModelScope.launch {
            HotwordEventBus.wakeWordDetected.collect { startSession() }
        }

        if (HotwordEventBus.consumePendingWakeWord()) {
            startSession()
        }
    }

    fun startSession(conversationId: String? = null) {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return

        HotwordEventBus.isPaused.value = true
        disconnect(resumeHotword = false)
        // Set isConnecting synchronously BEFORE launching the coroutine so that any
        // subsequent call to startSession() on the main thread sees the flag immediately
        // and returns early — prevents two WebSocket connections if startSession() is
        // called twice before the coroutine runs.
        resumeEventAcked = false
        _uiState.update { it.copy(isConnecting = true) }

        viewModelScope.launch {
            val jwt = getProductJwt()
            if (jwt.isBlank()) {
                setError("Not signed in — please log in again.")
                HotwordEventBus.resume()
                return@launch
            }

            if (conversationId != null) {
                val history = runCatching {
                    conversationRepository.fetchTurns(jwt, conversationId)
                }.getOrDefault(emptyList())
                if (history.isNotEmpty()) {
                    _uiState.update { it.copy(turns = history) }
                }
            }

            val resolvedConversationId = conversationId ?: runCatching {
                tokenRepository.startVoiceSession(null)
            }.getOrElse { e ->
                Log.e(TAG, "Failed to start voice session: ${e.message}", e)
                setError("Could not start session: ${e.message}")
                HotwordEventBus.resume()
                return@launch
            }

            // Restore any stored PRD from a previous awaiting_confirmation state.
            // SharedPreferences survive app backgrounding and system kills, so this
            // rehydrates the confirmation card without requiring a WebSocket reconnect.
            val storedState = loadConversationState(resolvedConversationId)
            val storedPrd = loadPrdSummary(resolvedConversationId)
            if (storedState == "awaiting_confirmation") {
                _uiState.update {
                    it.copy(
                        conversationState = "awaiting_confirmation",
                        prdSummary = storedPrd,
                    )
                }
            }

            val client = BackendVoiceClient(
                backendUrl = BackendConfig.wsBaseUrl,
                productJwt = jwt,
            )
            val player = PcmAudioPlayer()
            player.start(viewModelScope)

            backendClient = client
            audioPlayer = player

            _uiState.update {
                it.copy(
                    isConnected = false,
                    isMicStreaming = false,
                    statusMessage = "Connecting to Gervis...",
                    conversationId = resolvedConversationId,
                )
            }

            connectTimeoutJob?.cancel()
            connectTimeoutJob = viewModelScope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (_uiState.value.isConnecting && !_uiState.value.isConnected) {
                    setError("Connection timed out.")
                    disconnect()
                }
            }

            clientEventsJob?.cancel()
            clientEventsJob = viewModelScope.launch {
                client.events.collect { event -> handleClientEvent(event) }
            }

            client.connect(resolvedConversationId)
        }
    }

    fun disconnect(resumeHotword: Boolean = true) {
        if (resumeHotword) HotwordEventBus.resume()
        cancelTimers()
        stopMicrophone(sendEndOfSpeech = true)
        clientEventsJob?.cancel()
        clientEventsJob = null
        backendClient?.close()
        backendClient = null
        audioPlayer?.stop()
        audioPlayer = null

        _uiState.update {
            it.copy(
                isConnecting = false,
                isConnected = false,
                isMicStreaming = false,
                statusMessage = "Disconnected",
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(recentError = null) }
    }

    private fun handleClientEvent(event: VoiceClientEvent) {
        when (event) {
            is VoiceClientEvent.Connected -> {
                connectTimeoutJob?.cancel()
                // Proactively send location on connect so the backend cache is warm
                // before the user speaks. The on-demand request_location flow is still
                // the primary path; this just avoids a round-trip on the first map query.
                viewModelScope.launch {
                    val ctx = resolveLocationContext()
                    if (ctx != null) {
                        backendClient?.sendLocationResponse(
                            ctx.latitude,
                            ctx.longitude,
                            ctx.accuracyMeters,
                            ctx.locationLabel,
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        statusMessage = "Connected — listening...",
                    )
                }
                playActivationSound()
                startMicrophone()
                resetInactivityTimer()
            }

            is VoiceClientEvent.AudioChunk -> {
                audioPlayer?.enqueue(event.bytes)
            }

            is VoiceClientEvent.Interrupted -> {
                audioPlayer?.clear()
                _uiState.update { it.copy(statusMessage = "Interrupted") }
            }

            is VoiceClientEvent.InputTranscript -> {
                _uiState.update { state ->
                    state.copy(turns = upsertTurn(state.turns, "user", event.text))
                }
                resetInactivityTimer()

                val lower = event.text.lowercase().trim()
                if (lower.contains("goodbye") || lower.contains("good bye")) {
                    Log.i(TAG, "Goodbye detected — ending voice session.")
                    disconnect()
                }
            }

            is VoiceClientEvent.OutputTranscript -> {
                _uiState.update { state ->
                    state.copy(turns = upsertTurn(state.turns, "assistant", event.text))
                }
                resetInactivityTimer()
                // Ack any pending resume events after Gervis delivers its first spoken
                // output (the welcome-back message). The endpoint is idempotent so calling
                // it when there are no pending events is safe.
                val convId = _uiState.value.conversationId
                if (!resumeEventAcked && convId != null) {
                    resumeEventAcked = true
                    viewModelScope.launch {
                        val jwt = getProductJwt()
                        if (jwt.isNotBlank()) {
                            runCatching { conversationRepository.ackResumeEvent(jwt, convId) }
                                .onFailure { e -> Log.w(TAG, "ack-resume-event failed: ${e.message}") }
                        }
                    }
                }
            }

            is VoiceClientEvent.StateUpdate -> {
                val state = event.state
                val convId = _uiState.value.conversationId
                // Persist or clear state + PRD in SharedPreferences so the card survives
                // app backgrounding. Clear on terminal states (running_job / idle).
                if (convId != null) {
                    when (state) {
                        "awaiting_confirmation" -> {
                            storeConversationState(convId, state)
                            event.prdSummary?.let { storePrdSummary(convId, it) }
                        }
                        "running_job", "idle" -> {
                            clearConversationState(convId)
                            clearPrdSummary(convId)
                        }
                    }
                }
                _uiState.update { current ->
                    current.copy(
                        statusMessage = state,
                        conversationState = state,
                        prdSummary = when (state) {
                            "awaiting_confirmation" -> event.prdSummary ?: current.prdSummary
                            "running_job", "idle"   -> null
                            else                    -> current.prdSummary
                        },
                    )
                }
            }

            is VoiceClientEvent.JobStarted -> {
                _uiState.update { it.copy(activeJobDescription = event.description) }
            }

            is VoiceClientEvent.JobUpdate -> {
                if (event.status == "completed" || event.status == "failed") {
                    _uiState.update { it.copy(activeJobDescription = "") }
                }
            }

            is VoiceClientEvent.LocationRequest -> {
                viewModelScope.launch {
                    val ctx = resolveLocationContext()
                    if (ctx != null) {
                        backendClient?.sendLocationResponse(
                            ctx.latitude,
                            ctx.longitude,
                            ctx.accuracyMeters,
                            ctx.locationLabel,
                        )
                    } else {
                        Log.w(TAG, "Location requested by backend but location is unavailable.")
                    }
                }
            }

            is VoiceClientEvent.Error -> {
                connectTimeoutJob?.cancel()
                setError(event.message)
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isMicStreaming = false,
                        statusMessage = event.message,
                    )
                }
            }

            is VoiceClientEvent.Disconnected -> {
                connectTimeoutJob?.cancel()
                cancelTimers()
                HotwordEventBus.resume()
                stopMicrophone(sendEndOfSpeech = false)
                clientEventsJob?.cancel()
                clientEventsJob = null
                backendClient = null
                audioPlayer?.stop()
                audioPlayer = null
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isMicStreaming = false,
                        statusMessage = event.reason.ifBlank { "Disconnected" },
                    )
                }
            }

            VoiceClientEvent.Connecting -> {
                _uiState.update { it.copy(statusMessage = "Connecting...") }
            }
        }
    }

    private fun startMicrophone() {
        if (_uiState.value.isMicStreaming) return
        val client = backendClient ?: return
        val recorder = audioRecorder ?: AndroidAudioRecorder().also { audioRecorder = it }

        val started = recorder.start(viewModelScope) { chunk ->
            // Always forward mic audio — hardware AEC (VOICE_COMMUNICATION source +
            // AcousticEchoCanceler) handles echo suppression at the correct layer.
            // Muting the mic here would prevent barge-in: Gemini would never hear the
            // user's voice, never send `interrupted`, and the player would never clear.
            lastAudioSentTime = System.currentTimeMillis()
            client.sendAudioChunk(chunk)
        }

        if (!started) {
            setError("Microphone could not be started.")
            return
        }

        _uiState.update { it.copy(isMicStreaming = true) }
    }

    private fun stopMicrophone(sendEndOfSpeech: Boolean) {
        audioRecorder?.stop()
        audioRecorder = null
        if (sendEndOfSpeech) {
            backendClient?.sendEndOfSpeech()
        }
        _uiState.update { it.copy(isMicStreaming = false) }
    }

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            while (true) {
                delay(INACTIVITY_TIMEOUT_MS)
                // Don't disconnect while Gervis is still playing audio.
                if (audioPlayer?.hasPendingAudio == true) continue
                // Don't disconnect while the user is actively speaking. Audio chunks
                // arrive ~every 64ms while speaking, so if one arrived recently the
                // user is mid-utterance and the transcript just hasn't come back yet.
                if (System.currentTimeMillis() - lastAudioSentTime < INACTIVITY_TIMEOUT_MS) continue
                Log.i(TAG, "Inactivity timeout — ending voice session.")
                backendClient?.sendEndOfSpeech()
                disconnect()
                break
            }
        }
    }

    private fun cancelTimers() {
        inactivityJob?.cancel()
        inactivityJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    private fun initActivationSound() {
        val pool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        soundPool = pool
        activationSoundId = runCatching {
            pool.load(getApplication(), R.raw.activation_sound, 1)
        }.getOrDefault(0)
    }

    private fun playActivationSound() {
        if (activationSoundId > 0) {
            soundPool?.play(activationSoundId, 1f, 1f, 0, 0, 1f)
        }
    }

    private fun observeConnectedDevices() {
        viewModelScope.launch {
            ConnectedDeviceMonitor.state.collect { state ->
                _uiState.update { it.copy(isWakeWordDeviceConnected = state.isWakeWordReady) }
            }
        }
    }

    private suspend fun resolveLocationContext(): UserLocationContext? {
        if (!AppPreferences.isLocationSharingEnabled(getApplication())) return null
        return runCatching { userLocationRepository.getLocationContext() }
            .onFailure { e -> Log.w(TAG, "Could not resolve location context: ${e.message}") }
            .getOrNull()
    }

    private fun upsertTurn(
        turns: List<ConversationTurn>,
        role: String,
        text: String,
    ): List<ConversationTurn> {
        val last = turns.lastOrNull()
        return if (last != null && last.role == role) {
            turns.dropLast(1) + ConversationTurn(role, text)
        } else {
            turns + ConversationTurn(role, text)
        }
    }

    /**
     * Submit a PRD confirmation or change request via REST.
     * On success the card is dismissed optimistically; the backend follows up with a
     * state_update (running_job or idle) over the WebSocket.
     *
     * @param conversationId the active conversation
     * @param confirmed      true → "Build it"; false → user wants to change something
     * @param changeRequest  non-null only when confirmed=false; the user's change text
     */
    fun confirmPrd(conversationId: String, confirmed: Boolean, changeRequest: String?) {
        viewModelScope.launch {
            val jwt = getProductJwt()
            if (jwt.isBlank()) {
                setError("Not signed in — please log in again.")
                return@launch
            }
            val success = runCatching {
                conversationRepository.confirmPrd(jwt, conversationId, confirmed, changeRequest)
            }.getOrDefault(false)

            if (success) {
                // Optimistically dismiss the card — the WebSocket state_update will follow
                _uiState.update { it.copy(prdSummary = null) }
                clearPrdSummary(conversationId)
                if (!confirmed) {
                    // "Change" path: also clear the stored state so the fallback is hidden
                    clearConversationState(conversationId)
                    _uiState.update { it.copy(conversationState = "idle") }
                }
            } else {
                setError("Could not submit your response. Please try again.")
            }
        }
    }

    // ── SharedPreferences helpers for PRD persistence ────────────────────────

    private fun prefs() = getApplication<Application>()
        .getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)

    private fun storePrdSummary(conversationId: String, prd: PrdSummary) {
        prefs().edit { putString("prd_summary_$conversationId", prd.toJson()) }
    }

    private fun loadPrdSummary(conversationId: String): PrdSummary? {
        val raw = prefs().getString("prd_summary_$conversationId", null) ?: return null
        return PrdSummary.fromJsonString(raw)
    }

    private fun clearPrdSummary(conversationId: String) {
        prefs().edit { remove("prd_summary_$conversationId") }
    }

    private fun storeConversationState(conversationId: String, state: String) {
        prefs().edit { putString("conv_state_$conversationId", state) }
    }

    private fun loadConversationState(conversationId: String): String =
        prefs().getString("conv_state_$conversationId", "") ?: ""

    private fun clearConversationState(conversationId: String) {
        prefs().edit { remove("conv_state_$conversationId") }
    }

    private fun getProductJwt(): String = tokenRepository.getProductJwt()

    private fun setError(message: String) {
        _uiState.update { it.copy(recentError = message) }
    }

    override fun onCleared() {
        disconnect()
        soundPool?.release()
        soundPool = null
        super.onCleared()
    }
}
