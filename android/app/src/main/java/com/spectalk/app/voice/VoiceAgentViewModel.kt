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
import com.spectalk.app.device.CaptureResult
import com.spectalk.app.device.ConnectedDeviceMonitor
import com.spectalk.app.device.GlassesCameraManager
import com.spectalk.app.device.MetaWearablesAccessManager
import com.spectalk.app.gallery.GalleryRepository
import com.spectalk.app.hotword.HotwordEventBus
import com.spectalk.app.location.UserLocationContext
import com.spectalk.app.settings.AppPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VoiceAgentViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VoiceAgentViewModel"
        private const val INACTIVITY_TIMEOUT_MS = 20_000L
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
    private var glassesSessionStarted = false
    @Volatile private var isChatScreenForeground = false

    // Tracks whether we've sent ack-resume-event for the current session.
    // Reset on each new session so the ack fires exactly once per reconnect.
    @Volatile private var resumeEventAcked = false

    private var audioRecorder: AndroidAudioRecorder? = null
    private var audioPlayer: PcmAudioPlayer? = null

    private var soundPool: SoundPool? = null
    private var activationSoundId: Int = 0
    @Volatile private var activationSoundLoaded = false
    @Volatile private var pendingActivationSound = false
    private var pendingUserTranscript: String? = null
    private var pendingAssistantTranscript: String? = null

    // Updated on every outgoing audio chunk. The inactivity timer checks this so it
    // doesn't fire while the user is actively speaking (before a transcript arrives).
    @Volatile private var lastAudioSentTime: Long = 0L

    init {
        initActivationSound()
        observeConnectedDevices()
        observeMetaWearablesAccess()
        observeGlassesCameraReadiness()

        viewModelScope.launch {
            HotwordEventBus.wakeWordDetected.collect { startSession() }
        }

        viewModelScope.launch {
            VoiceSessionCommandBus.resumeRequests.collect { conversationId ->
                if (!ConnectedDeviceMonitor.state.value.isWakeWordReady) {
                    Log.i(
                        TAG,
                        "Ignoring notification resume for $conversationId - no wearable audio route is active.",
                    )
                    return@collect
                }

                Log.i(
                    TAG,
                    "Notification requested wearable voice resume for conversation $conversationId",
                )
                resumeConversationFromNotification(conversationId)
            }
        }

        if (HotwordEventBus.consumePendingWakeWord()) {
            startSession()
        }
    }

    fun startSession(conversationId: String? = null) {
        val currentConversationId = _uiState.value.conversationId
        val switchingConversation = conversationId != currentConversationId
        if ((_uiState.value.isConnecting || _uiState.value.isConnected) && !switchingConversation) {
            return
        }

        HotwordEventBus.isPaused.value = true
        disconnect(resumeHotword = false)
        // Set isConnecting synchronously BEFORE launching the coroutine so that any
        // subsequent call to startSession() on the main thread sees the flag immediately
        // and returns early — prevents two WebSocket connections if startSession() is
        // called twice before the coroutine runs.
        resumeEventAcked = false
        pendingUserTranscript = null
        pendingAssistantTranscript = null
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
            val player = PcmAudioPlayer(getApplication())
            player.start(
                scope = viewModelScope,
                preferSpeaker = !ConnectedDeviceMonitor.state.value.isWakeWordReady,
            )

            backendClient = client
            audioPlayer = player

            _uiState.update {
                it.copy(
                    isConnected = false,
                    isMicStreaming = false,
                    isListeningEnabled = true,
                    isAudioPlaybackEnabled = true,
                    statusMessage = "Connecting to Gervis...",
                    conversationId = resolvedConversationId,
                    draftText = "",
                    isMetaRegistered = MetaWearablesAccessManager.state.value.isRegistered,
                    hasMetaCameraPermission = MetaWearablesAccessManager.state.value.hasCameraPermission,
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

            val preferredVoiceLanguage = AppPreferences
                .getAgentVoiceLanguage(getApplication())
                .prefValue
            val preferredNetworkHost = AppPreferences.getProjectNetworkHost(getApplication())
            client.connect(
                conversationId = resolvedConversationId,
                voiceLanguage = preferredVoiceLanguage,
                tailscaleHost = preferredNetworkHost,
            )

            syncGlassesCameraSession(ConnectedDeviceMonitor.state.value.hasMetaWearable)
        }
    }

    fun disconnect(resumeHotword: Boolean = true) {
        cancelTimers()
        stopMicrophone(sendEndOfSpeech = true)
        clientEventsJob?.cancel()
        clientEventsJob = null
        backendClient?.close()
        backendClient = null
        // Stop the player (and restore audio route from MODE_IN_COMMUNICATION)
        // BEFORE resuming the hotword service — Vosk's AudioRecord can fail to
        // capture properly while MODE_IN_COMMUNICATION is still active.
        audioPlayer?.stop()
        audioPlayer = null
        if (resumeHotword) HotwordEventBus.resume()
        stopGlassesCameraSession()
        pendingUserTranscript = null
        pendingAssistantTranscript = null

        _uiState.update {
            it.copy(
                isConnecting = false,
                isConnected = false,
                isMicStreaming = false,
                isListeningEnabled = true,
                isAudioPlaybackEnabled = true,
                statusMessage = "Disconnected",
                isGlassesCameraReady = false,
                isMetaRegistered = MetaWearablesAccessManager.state.value.isRegistered,
                hasMetaCameraPermission = MetaWearablesAccessManager.state.value.hasCameraPermission,
            )
        }
    }

    fun reconnect() {
        startSession(_uiState.value.conversationId)
    }

    fun resumeConversationFromNotification(conversationId: String) {
        if (!ConnectedDeviceMonitor.state.value.isWakeWordReady) {
            Log.i(
                TAG,
                "Skipping notification resume for $conversationId because no wearable route is active.",
            )
            return
        }

        val currentConversationId = _uiState.value.conversationId
        val sameConversation =
            currentConversationId != null &&
                currentConversationId == conversationId &&
                (_uiState.value.isConnected || _uiState.value.isConnecting)

        if (sameConversation) {
            Log.i(
                TAG,
                "Notification resume matches the active conversation - reconnecting to fetch the finished job result.",
            )
            disconnect(resumeHotword = false)
        }

        startSession(conversationId)
    }

    fun onChatScreenStarted() {
        isChatScreenForeground = true
    }

    fun onChatScreenInactive() {
        isChatScreenForeground = false
        if (!(_uiState.value.isConnected || _uiState.value.isConnecting)) return

        if (!canContinueSessionInBackground()) {
            Log.i(TAG, "Chat screen inactive without wearable voice mode - ending voice session.")
            disconnect()
        } else {
            Log.i(TAG, "Chat screen inactive - keeping wearable voice session alive until inactivity timeout.")
        }
    }

    fun onChatScreenStopped() {
        onChatScreenInactive()
    }

    fun setDraftText(text: String) {
        _uiState.update { it.copy(draftText = text) }
    }

    fun toggleListeningEnabled() {
        val state = _uiState.value
        if (!state.isConnected) return

        if (state.isListeningEnabled) {
            cancelInactivityOnly()
            stopMicrophone(sendEndOfSpeech = true)
            audioPlayer?.clear()
            _uiState.update {
                it.copy(
                    isListeningEnabled = false,
                    isAudioPlaybackEnabled = false,
                    statusMessage = "Text mode",
                )
            }
            syncSessionCapabilities()
        } else {
            val started = startMicrophone()
            if (started) {
                _uiState.update {
                    it.copy(
                        isListeningEnabled = true,
                        isAudioPlaybackEnabled = true,
                        statusMessage = "Connected — listening...",
                    )
                }
                syncSessionCapabilities()
                if (_uiState.value.isListeningEnabled) {
                    resetInactivityTimer()
                }
            }
        }
    }

    fun sendDraftText() {
        val client = backendClient ?: return
        val text = _uiState.value.draftText.trim()
        if (text.isBlank()) return

        client.sendTextInput(text)
        _uiState.update { state ->
            state.copy(
                draftText = "",
                turns = appendCommittedTurn(state.turns, "user", text),
            )
        }
        if (_uiState.value.isListeningEnabled) {
            resetInactivityTimer()
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
                if (_uiState.value.isListeningEnabled && startMicrophone()) {
                    resetInactivityTimer()
                }
                syncSessionCapabilities()
            }

            is VoiceClientEvent.AudioChunk -> {
                if (_uiState.value.isAudioPlaybackEnabled) {
                    audioPlayer?.enqueue(event.bytes)
                }
            }

            is VoiceClientEvent.Interrupted -> {
                audioPlayer?.clear()
                pendingAssistantTranscript = null
                _uiState.update { state ->
                    state.copy(
                        statusMessage = "Interrupted",
                        turns = appendSystemTurn(state.turns, "You interrupted Gervis"),
                    )
                }
            }

            is VoiceClientEvent.InputTranscript -> {
                pendingUserTranscript = mergeTranscriptFragment(pendingUserTranscript, event.text)
                if (_uiState.value.isListeningEnabled) {
                    resetInactivityTimer()
                }

                val lower = pendingUserTranscript.orEmpty().lowercase().trim()
                if (!event.isPartial && (lower.contains("goodbye") || lower.contains("good bye"))) {
                    Log.i(TAG, "Goodbye detected — ending voice session.")
                    disconnect()
                }
            }

            is VoiceClientEvent.OutputTranscript -> {
                pendingAssistantTranscript = mergeTranscriptFragment(pendingAssistantTranscript, event.text)
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

            is VoiceClientEvent.ToolStatus -> {
                _uiState.update { state ->
                    state.copy(
                        turns = upsertActivityTurn(
                            turns = state.turns,
                            activityId = event.activityId,
                            label = event.label,
                            status = event.status,
                            durationMs = event.durationMs,
                        )
                    )
                }
            }

            VoiceClientEvent.TurnComplete -> {
                flushPendingTranscript(role = "user")
                flushPendingTranscript(role = "assistant")
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
                    _uiState.update { state ->
                        state.copy(
                            activeJobDescription = "",
                            turns = if (event.message.isNotBlank()) {
                                appendCommittedTurn(state.turns, "assistant", event.message)
                            } else {
                                state.turns
                            },
                        )
                    }
                }
            }

            is VoiceClientEvent.RequestVisualCapture -> {
                if (event.source == "glasses" &&
                    _uiState.value.isConnected &&
                    _uiState.value.isListeningEnabled
                ) {
                    sendGlassesFrame(requestedByAgent = true)
                } else {
                    backendClient?.sendVisualCaptureFailure(
                        when {
                            !_uiState.value.isConnected -> "No active voice session"
                            !_uiState.value.isListeningEnabled -> "Listening mode is turned off"
                            else -> "Automatic glasses capture is unavailable"
                        }
                    )
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
                        isListeningEnabled = true,
                        isAudioPlaybackEnabled = true,
                        statusMessage = event.message,
                    )
                }
            }

            is VoiceClientEvent.Disconnected -> {
                connectTimeoutJob?.cancel()
                cancelTimers()
                stopMicrophone(sendEndOfSpeech = false)
                clientEventsJob?.cancel()
                clientEventsJob = null
                backendClient = null
                // Stop player and restore audio route before resuming hotword.
                audioPlayer?.stop()
                audioPlayer = null
                HotwordEventBus.resume()
                pendingUserTranscript = null
                pendingAssistantTranscript = null
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isMicStreaming = false,
                        isListeningEnabled = true,
                        isAudioPlaybackEnabled = true,
                        statusMessage = event.reason.ifBlank { "Disconnected" },
                    )
                }
            }

            is VoiceClientEvent.SessionTimeout -> {
                // Expected Gemini Live ~10-min hard limit — not a crash.
                // Stop audio, tear down the session cleanly, resume wake word.
                connectTimeoutJob?.cancel()
                cancelTimers()
                audioPlayer?.clear()
                audioPlayer?.stop()
                audioPlayer = null
                stopMicrophone(sendEndOfSpeech = false)
                backendClient?.close()
                backendClient = null
                clientEventsJob?.cancel()
                clientEventsJob = null
                pendingUserTranscript = null
                pendingAssistantTranscript = null
                HotwordEventBus.resume()
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        isMicStreaming = false,
                        // Show as informational status — not an error color/screen.
                        statusMessage = event.message,
                    )
                }
            }

            VoiceClientEvent.Connecting -> {
                _uiState.update { it.copy(statusMessage = "Connecting...") }
            }
        }
    }

    private fun startMicrophone(): Boolean {
        if (_uiState.value.isMicStreaming) return true
        val client = backendClient ?: return false
        val recorder = audioRecorder ?: AndroidAudioRecorder().also { audioRecorder = it }

        val started = recorder.start(viewModelScope) { chunk ->
            // Send mic audio unconditionally — hardware AEC (VOICE_COMMUNICATION
            // audio source + VOICE_COMMUNICATION AudioTrack + AcousticEchoCanceler)
            // handles echo suppression.  Both recorder and player share the same
            // VoIP audio path so AEC can use playback as its reference signal.
            // Muting the mic here would prevent barge-in.
            lastAudioSentTime = System.currentTimeMillis()
            client.sendAudioChunk(chunk)
        }

        if (!started) {
            setError("Microphone could not be started.")
            return false
        }

        _uiState.update { it.copy(isMicStreaming = true) }
        return true
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
        if (!_uiState.value.isListeningEnabled) return
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

    private fun cancelInactivityOnly() {
        inactivityJob?.cancel()
        inactivityJob = null
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
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (sampleId == activationSoundId && status == 0) {
                activationSoundLoaded = true
                if (pendingActivationSound) {
                    pendingActivationSound = false
                    pool.play(activationSoundId, 1f, 1f, 0, 0, 1f)
                }
            }
        }
        soundPool = pool
        activationSoundId = runCatching {
            pool.load(getApplication(), R.raw.activation_sound, 1)
        }.getOrDefault(0)
    }

    private fun playActivationSound() {
        if (activationSoundId > 0 && activationSoundLoaded) {
            soundPool?.play(activationSoundId, 1f, 1f, 0, 0, 1f)
        } else if (activationSoundId > 0) {
            pendingActivationSound = true
        }
    }

    private fun observeConnectedDevices() {
        viewModelScope.launch {
            ConnectedDeviceMonitor.state.collect { state ->
                audioPlayer?.setPreferSpeaker(!state.isWakeWordReady)
                _uiState.update { it.copy(isWakeWordDeviceConnected = state.isWakeWordReady) }
                syncGlassesCameraSession(state.hasMetaWearable)
                syncSessionCapabilities()
                if (!isChatScreenForeground &&
                    (_uiState.value.isConnected || _uiState.value.isConnecting) &&
                    !canContinueSessionInBackground()
                ) {
                    Log.i(TAG, "Wearable audio unavailable while app is backgrounded - ending voice session.")
                    disconnect()
                }
            }
        }
    }

    private fun observeMetaWearablesAccess() {
        viewModelScope.launch {
            MetaWearablesAccessManager.state.collect { accessState ->
                _uiState.update {
                    it.copy(
                        isMetaRegistered = accessState.isRegistered,
                        hasMetaCameraPermission = accessState.hasCameraPermission,
                    )
                }
                syncGlassesCameraSession(ConnectedDeviceMonitor.state.value.hasMetaWearable)
            }
        }
    }

    private fun observeGlassesCameraReadiness() {
        viewModelScope.launch {
            GlassesCameraManager.isReady.collect { ready ->
                _uiState.update { it.copy(isGlassesCameraReady = ready) }
                syncSessionCapabilities()
            }
        }
    }

    private fun syncGlassesCameraSession(hasMetaWearable: Boolean) {
        val accessState = MetaWearablesAccessManager.state.value
        if (
            !(_uiState.value.isConnected || _uiState.value.isConnecting) ||
            !hasMetaWearable ||
            !accessState.canUseCamera
        ) {
            stopGlassesCameraSession()
        }
    }

    private fun stopGlassesCameraSession() {
        if (glassesSessionStarted) {
            GlassesCameraManager.stopSession()
            glassesSessionStarted = false
        } else {
            _uiState.update { it.copy(isGlassesCameraReady = false) }
        }
    }

    private fun syncSessionCapabilities() {
        backendClient?.sendSessionCapabilities(
            glassesCameraReady = _uiState.value.isGlassesCameraReady,
            glassesCaptureAvailable = isGlassesCaptureAvailable(),
            listeningEnabled = _uiState.value.isListeningEnabled,
        )
    }

    private fun canContinueSessionInBackground(): Boolean =
        _uiState.value.isConnected &&
            _uiState.value.isListeningEnabled &&
            _uiState.value.isWakeWordDeviceConnected

    private fun isGlassesCaptureAvailable(): Boolean =
        ConnectedDeviceMonitor.state.value.hasMetaWearable &&
            _uiState.value.isMetaRegistered &&
            _uiState.value.hasMetaCameraPermission

    private suspend fun resolveLocationContext(): UserLocationContext? {
        if (!AppPreferences.isLocationSharingEnabled(getApplication())) return null
        return runCatching { userLocationRepository.getLocationContext() }
            .onFailure { e -> Log.w(TAG, "Could not resolve location context: ${e.message}") }
            .getOrNull()
    }

    private fun mergeTranscriptFragment(current: String?, incoming: String): String? {
        val next = incoming.trim()
        if (next.isBlank()) return current

        val existing = current?.trim().orEmpty()
        return when {
            existing.isBlank() -> next
            existing == next -> existing
            next.startsWith(existing) -> next
            else -> "$existing $next"
        }
    }

    private fun appendCommittedTurn(
        turns: List<ConversationTurn>,
        role: String,
        text: String,
    ): List<ConversationTurn> {
        val last = turns.lastOrNull()
        return if (
            last != null &&
            last.kind == "message" &&
            last.role == role &&
            last.text == text
        ) {
            turns
        } else {
            turns + ConversationTurn(role, text)
        }
    }

    private fun appendSystemTurn(
        turns: List<ConversationTurn>,
        text: String,
    ): List<ConversationTurn> {
        val last = turns.lastOrNull()
        return if (last?.kind == "interruption" && last.text == text) {
            turns
        } else {
            turns + ConversationTurn(role = "system", text = text, kind = "interruption")
        }
    }

    private fun appendImageTurn(
        turns: List<ConversationTurn>,
        text: String,
        imagePath: String?,
    ): List<ConversationTurn> {
        return turns + ConversationTurn(
            role = "user",
            text = text,
            kind = "image",
            imagePath = imagePath,
        )
    }

    private fun upsertActivityTurn(
        turns: List<ConversationTurn>,
        activityId: String,
        label: String,
        status: String,
        durationMs: Long?,
    ): List<ConversationTurn> {
        val existingIndex = turns.indexOfLast {
            it.kind == "activity" && it.activityId == activityId
        }
        val updatedTurn = ConversationTurn(
            role = "system",
            text = label,
            kind = "activity",
            activityId = activityId,
            activityStatus = status,
            activityDurationMs = durationMs,
        )

        return if (existingIndex >= 0) {
            turns.toMutableList().apply { set(existingIndex, updatedTurn) }
        } else {
            turns + updatedTurn
        }
    }

    private fun flushPendingTranscript(role: String) {
        val pending = when (role) {
            "user" -> pendingUserTranscript
            "assistant" -> pendingAssistantTranscript
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return

        if (role == "user") {
            pendingUserTranscript = null
        } else {
            pendingAssistantTranscript = null
        }

        _uiState.update { state ->
            state.copy(turns = appendCommittedTurn(state.turns, role, pending))
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
            val networkHost = AppPreferences.getProjectNetworkHost(getApplication())
            val success = runCatching {
                conversationRepository.confirmPrd(
                    jwt = jwt,
                    conversationId = conversationId,
                    confirmed = confirmed,
                    changeRequest = changeRequest,
                    networkHost = networkHost.ifBlank { null },
                )
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

    fun sendGlassesFrame(requestedByAgent: Boolean = false) {
        val client = backendClient ?: return
        Log.i(
            TAG,
            "Glasses capture requested " +
                "(requestedByAgent=$requestedByAgent, " +
                "hasMeta=${ConnectedDeviceMonitor.state.value.hasMetaWearable}, " +
                "registered=${_uiState.value.isMetaRegistered}, " +
                "cameraPermission=${_uiState.value.hasMetaCameraPermission}, " +
                "streamReady=${_uiState.value.isGlassesCameraReady})",
        )
        client.sendVisualCaptureDebug(
            "capture_requested requestedByAgent=$requestedByAgent " +
                "hasMeta=${ConnectedDeviceMonitor.state.value.hasMetaWearable} " +
                "registered=${_uiState.value.isMetaRegistered} " +
                "cameraPermission=${_uiState.value.hasMetaCameraPermission} " +
                "streamReady=${_uiState.value.isGlassesCameraReady}"
        )
        if (!ConnectedDeviceMonitor.state.value.hasMetaWearable) {
            setError("Meta glasses are not connected right now.")
            client.sendVisualCaptureFailure("Meta glasses are not connected right now")
            return
        }
        if (!_uiState.value.isMetaRegistered) {
            setError("Meta glasses are not connected to SpecTalk yet.")
            client.sendVisualCaptureFailure("Meta glasses are not connected to SpecTalk")
            return
        }
        if (!_uiState.value.hasMetaCameraPermission) {
            setError("Meta camera permission has not been granted yet.")
            client.sendVisualCaptureFailure("Meta camera permission has not been granted")
            return
        }
        viewModelScope.launch {
            if (!glassesSessionStarted) {
                GlassesCameraManager.startSession(getApplication())
                glassesSessionStarted = true
            }
            syncSessionCapabilities()
            val result = GlassesCameraManager.capturePhoto()
            when (result) {
                is CaptureResult.Success -> {
                    val savedImagePath = runCatching {
                        GalleryRepository
                            .saveImage(getApplication(), result.jpegBytes, "glasses")
                            .absolutePath
                    }.onFailure { error ->
                        Log.w(TAG, "Could not save glasses capture to gallery: ${error.message}")
                    }.getOrNull()

                    _uiState.update { state ->
                        state.copy(
                            turns = appendImageTurn(
                                state.turns,
                                if (requestedByAgent) {
                                    "Captured your current view"
                                } else {
                                    "Shared your glasses view"
                                },
                                savedImagePath,
                            )
                        )
                    }
                    client.sendImage(
                        result.jpegBytes,
                        source = if (requestedByAgent) "glasses_auto" else "glasses_manual",
                    )
                    if (_uiState.value.isListeningEnabled) {
                        resetInactivityTimer()
                    }
                    Log.i(TAG, "Glasses frame sent (${result.jpegBytes.size} bytes)")
                }
                is CaptureResult.Failure -> {
                    Log.w(TAG, "Glasses capture failed: ${result.reason}")
                    client.sendVisualCaptureFailure(result.reason)
                    setError("Could not capture from glasses: ${result.reason}")
                }
            }
            stopGlassesCameraSession()
            syncSessionCapabilities()
        }
    }

    /** Send a JPEG captured from the phone camera to Gervis and save it to the gallery. */
    fun sendCameraImage(jpegBytes: ByteArray) {
        val client = backendClient ?: return
        viewModelScope.launch {
            val savedImagePath = runCatching {
                GalleryRepository
                    .saveImage(getApplication(), jpegBytes, "camera")
                    .absolutePath
            }.onFailure { error ->
                Log.w(TAG, "Could not save camera image to gallery: ${error.message}")
            }.getOrNull()

            _uiState.update { state ->
                state.copy(
                    turns = appendImageTurn(
                        state.turns,
                        "Shared a photo",
                        savedImagePath,
                    )
                )
            }
            if (_uiState.value.isListeningEnabled) {
                resetInactivityTimer()
            }
            client.sendImage(jpegBytes, source = "phone_camera")
            Log.i(TAG, "Camera image sent (${jpegBytes.size} bytes)")
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
        activationSoundLoaded = false
        pendingActivationSound = false
        super.onCleared()
    }
}

