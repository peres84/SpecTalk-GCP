package com.spectalk.app.voice

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectalk.app.R
import com.spectalk.app.SpecTalkApplication
import com.spectalk.app.config.BackendConfig
import com.spectalk.app.hotword.HotwordEventBus
import com.spectalk.app.hotword.HotwordService
import com.spectalk.app.audio.AndroidAudioRecorder
import com.spectalk.app.audio.PcmAudioPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VoiceAgentViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VoiceAgentViewModel"
        private const val INACTIVITY_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    private val tokenRepository = (application as SpecTalkApplication).tokenRepository

    private val _uiState = MutableStateFlow(VoiceSessionUiState())
    val uiState: StateFlow<VoiceSessionUiState> = _uiState.asStateFlow()

    private var backendClient: BackendVoiceClient? = null
    private var clientEventsJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var inactivityJob: Job? = null

    private var audioRecorder: AndroidAudioRecorder? = null
    private var audioPlayer: PcmAudioPlayer? = null

    private var soundPool: SoundPool? = null
    private var activationSoundId: Int = 0

    init {
        initActivationSound()
        observeBluetoothHeadset()

        // Wake word detected → start voice session automatically
        viewModelScope.launch {
            HotwordEventBus.wakeWordDetected.collect { startSession() }
        }

        // Pick up wake events that fired while the ViewModel was dead
        if (HotwordEventBus.consumePendingWakeWord()) {
            startSession()
        }
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    fun startSession(conversationId: String? = null) {
        if (_uiState.value.isConnecting || _uiState.value.isConnected) return

        // Pause hotword before taking the mic
        HotwordEventBus.isPaused.value = true
        disconnect(resumeHotword = false)

        // Play activation sound first, then open WebSocket
        viewModelScope.launch {
            playActivationSound()

            val jwt = getProductJwt()
            if (jwt.isBlank()) {
                setError("Not signed in — please log in again.")
                HotwordEventBus.resume()
                return@launch
            }

            // Get a conversation ID from the backend (or use the one provided, e.g. from a notification)
            val resolvedConversationId = conversationId ?: runCatching {
                tokenRepository.startVoiceSession()
            }.getOrElse { e ->
                Log.e(TAG, "Failed to start voice session: ${e.message}", e)
                setError("Could not start session: ${e.message}")
                HotwordEventBus.resume()
                return@launch
            }

            val client = BackendVoiceClient(backendUrl = BackendConfig.wsBaseUrl, productJwt = jwt)
            val player = PcmAudioPlayer()
            player.start(viewModelScope)

            backendClient = client
            audioPlayer = player

            _uiState.update {
                it.copy(
                    isConnecting = true,
                    isConnected = false,
                    isMicStreaming = false,
                    statusMessage = "Connecting to Gervis…",
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

    // ── Client event handler ─────────────────────────────────────────────────

    private fun handleClientEvent(event: VoiceClientEvent) {
        when (event) {
            is VoiceClientEvent.Connected -> {
                connectTimeoutJob?.cancel()
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = true,
                        statusMessage = "Connected — listening…",
                    )
                }
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
                _uiState.update { it.copy(latestUserTranscript = event.text) }
                resetInactivityTimer()

                // "Goodbye" detected → end session hands-free
                val lower = event.text.lowercase().trim()
                if (lower.contains("goodbye") || lower.contains("good bye")) {
                    Log.i(TAG, "Goodbye detected — ending voice session.")
                    disconnect()
                }
            }

            is VoiceClientEvent.OutputTranscript -> {
                _uiState.update { it.copy(latestAssistantTranscript = event.text) }
                resetInactivityTimer()
            }

            is VoiceClientEvent.StateUpdate -> {
                _uiState.update { it.copy(statusMessage = event.state) }
            }

            is VoiceClientEvent.JobStarted -> {
                _uiState.update { it.copy(activeJobDescription = event.description) }
            }

            is VoiceClientEvent.JobUpdate -> {
                if (event.status == "completed" || event.status == "failed") {
                    _uiState.update { it.copy(activeJobDescription = "") }
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
                _uiState.update { it.copy(statusMessage = "Connecting…") }
            }
        }
    }

    // ── Microphone ───────────────────────────────────────────────────────────

    private fun startMicrophone() {
        if (_uiState.value.isMicStreaming) return
        val client = backendClient ?: return
        val recorder = audioRecorder ?: AndroidAudioRecorder().also { audioRecorder = it }

        val started = recorder.start(viewModelScope) { chunk ->
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

    // ── Inactivity timer ─────────────────────────────────────────────────────

    private fun resetInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = viewModelScope.launch {
            while (true) {
                delay(INACTIVITY_TIMEOUT_MS)
                // Don't disconnect while Gervis is still speaking
                if (audioPlayer?.hasPendingAudio == true) continue
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

    // ── Activation sound ─────────────────────────────────────────────────────

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
        // Load returns 0 if the resource doesn't exist yet — safe to call
        activationSoundId = runCatching {
            pool.load(getApplication(), R.raw.activation_sound, 1)
        }.getOrDefault(0)
    }

    private suspend fun playActivationSound() {
        if (activationSoundId == 0) return
        soundPool?.play(activationSoundId, 1f, 1f, 0, 0, 1f)
        // Give the chime ~300ms to play before opening the WebSocket
        delay(300)
    }

    // ── Bluetooth headset monitoring ─────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun observeBluetoothHeadset() {
        val app = getApplication<Application>()
        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val alreadyConnected = btManager?.adapter
            ?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        if (alreadyConnected) {
            _uiState.update { it.copy(isBtHeadsetConnected = true) }
            startHotwordService()
        }

        viewModelScope.launch {
            callbackFlow {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        trySend(state == BluetoothProfile.STATE_CONNECTED)
                    }
                }
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                awaitClose { app.unregisterReceiver(receiver) }
            }.collect { isConnected ->
                _uiState.update { it.copy(isBtHeadsetConnected = isConnected) }
                if (isConnected) startHotwordService()
            }
        }
    }

    private fun startHotwordService() {
        HotwordEventBus.resume()
        runCatching {
            getApplication<Application>()
                .startForegroundService(Intent(getApplication(), HotwordService::class.java))
        }.onFailure { e -> Log.w(TAG, "Could not start HotwordService: ${e.message}") }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun getProductJwt(): String = tokenRepository.getProductJwt()

    private fun setError(message: String) {
        _uiState.update { it.copy(recentError = message) }
    }

    override fun onCleared() {
        disconnect()
        soundPool?.release()
        soundPool = null
        // Do NOT stop HotwordService here — it must keep running as a foreground
        // service so the wake word works when the app is in the background or the
        // screen is locked. The service self-manages via Bluetooth state.
        super.onCleared()
    }
}
