package com.spectalk.app.voice

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface VoiceClientEvent {
    data object Connected : VoiceClientEvent
    data object Connecting : VoiceClientEvent
    data class AudioChunk(val bytes: ByteArray) : VoiceClientEvent
    data class InputTranscript(val text: String) : VoiceClientEvent
    data class OutputTranscript(val text: String) : VoiceClientEvent
    data object Interrupted : VoiceClientEvent
    data class StateUpdate(val state: String) : VoiceClientEvent
    data class JobStarted(val jobId: String, val description: String) : VoiceClientEvent
    data class JobUpdate(val jobId: String, val status: String, val message: String) : VoiceClientEvent
    data class Error(val message: String) : VoiceClientEvent
    data class Disconnected(val reason: String) : VoiceClientEvent
}

/**
 * WebSocket client for the backend voice bridge.
 *
 * Connects to WS /ws/voice/{conversationId} on the backend.
 * - Sends raw PCM 16kHz binary frames from the mic to the backend.
 * - Receives raw PCM 24kHz binary frames for playback.
 * - Parses JSON control messages and emits typed [VoiceClientEvent]s.
 *
 * In Phase 1 the [backendUrl] can point to a local mock server.
 * Phase 3 will swap it for the real Cloud Run WebSocket URL.
 */
class BackendVoiceClient(
    private val backendUrl: String,
    private val productJwt: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        private const val TAG = "BackendVoiceClient"
    }

    private val _events = MutableSharedFlow<VoiceClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceClientEvent> = _events.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isConnected: Boolean = false

    fun connect(conversationId: String) {
        val url = "$backendUrl/ws/voice/$conversationId"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $productJwt")
            .build()

        _events.tryEmit(VoiceClientEvent.Connecting)

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened (HTTP ${response.code}).")
                isConnected = true
                _events.tryEmit(VoiceClientEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleControlMessage(text) }
                    .onFailure { e ->
                        _events.tryEmit(VoiceClientEvent.Error("Failed to parse control message: ${e.message}"))
                    }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Raw PCM audio from Gemini via backend — forward directly to player
                _events.tryEmit(VoiceClientEvent.AudioChunk(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                _events.tryEmit(VoiceClientEvent.Disconnected(
                    "Backend closing session (code=$code, reason=${reason.ifBlank { "empty" }})."
                ))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                _events.tryEmit(VoiceClientEvent.Disconnected(
                    "Disconnected from backend (code=$code, reason=${reason.ifBlank { "empty" }})."
                ))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _events.tryEmit(VoiceClientEvent.Error(t.message ?: "Backend connection failed."))
            }
        })
    }

    /** Send a raw PCM audio chunk (16kHz 16-bit mono) to the backend. */
    fun sendAudioChunk(pcmBytes: ByteArray) {
        if (!isConnected) return
        webSocket?.send(ByteString.of(*pcmBytes))
    }

    /** Signal end of user speech turn. */
    fun sendEndOfSpeech() {
        if (!isConnected) return
        webSocket?.send(JSONObject().put("type", "end_of_speech").toString())
    }

    /** Signal barge-in (user interrupted Gervis). */
    fun sendInterrupt() {
        if (!isConnected) return
        webSocket?.send(JSONObject().put("type", "interrupt").toString())
    }

    fun close(reason: String = "Client disconnect") {
        isConnected = false
        webSocket?.close(1000, reason)
        webSocket = null
    }

    private fun handleControlMessage(message: String) {
        Log.d(TAG, "WS ← ${message.take(300)}")
        val json = JSONObject(message)
        when (val type = json.optString("type")) {
            "interrupted" -> _events.tryEmit(VoiceClientEvent.Interrupted)
            "input_transcript" -> {
                val text = json.optString("text")
                if (text.isNotBlank()) _events.tryEmit(VoiceClientEvent.InputTranscript(text))
            }
            "output_transcript" -> {
                val text = json.optString("text")
                if (text.isNotBlank()) _events.tryEmit(VoiceClientEvent.OutputTranscript(text))
            }
            "state_update" -> {
                val state = json.optString("state")
                if (state.isNotBlank()) _events.tryEmit(VoiceClientEvent.StateUpdate(state))
            }
            "job_started" -> {
                val jobId = json.optString("job_id")
                val description = json.optString("description")
                _events.tryEmit(VoiceClientEvent.JobStarted(jobId, description))
            }
            "job_update" -> {
                val jobId = json.optString("job_id")
                val status = json.optString("status")
                val msg = json.optString("message")
                _events.tryEmit(VoiceClientEvent.JobUpdate(jobId, status, msg))
            }
            "error" -> {
                val msg = json.optString("message", "Unknown backend error.")
                _events.tryEmit(VoiceClientEvent.Error(msg))
            }
            else -> Log.d(TAG, "Unknown control message type: $type")
        }
    }
}
