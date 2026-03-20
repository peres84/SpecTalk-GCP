package com.spectalk.app.voice

import com.spectalk.app.location.UserLocationContext
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface VoiceClientEvent {
    data object Connected : VoiceClientEvent
    data object Connecting : VoiceClientEvent
    data class AudioChunk(val bytes: ByteArray) : VoiceClientEvent
    data class InputTranscript(val text: String, val isPartial: Boolean) : VoiceClientEvent
    data class OutputTranscript(val text: String, val isPartial: Boolean) : VoiceClientEvent
    data class ToolStatus(
        val activityId: String,
        val label: String,
        val status: String,
        val durationMs: Long? = null,
    ) : VoiceClientEvent
    data object Interrupted : VoiceClientEvent
    data object TurnComplete : VoiceClientEvent
    /**
     * Backend conversation state changed.
     * For "awaiting_confirmation", [prdSummary] carries the project spec to display.
     */
    data class StateUpdate(val state: String, val prdSummary: PrdSummary? = null) : VoiceClientEvent
    data class JobStarted(val jobId: String, val description: String) : VoiceClientEvent
    data class JobUpdate(val jobId: String, val status: String, val message: String) : VoiceClientEvent
    data class RequestVisualCapture(val source: String) : VoiceClientEvent
    /** Backend is requesting the device's current location (e.g. for the Maps tool). */
    data object LocationRequest : VoiceClientEvent
    data class Error(val message: String) : VoiceClientEvent
    data class Disconnected(val reason: String) : VoiceClientEvent
    /**
     * Gemini Live's ~10-minute session hard limit was reached.
     * This is expected behaviour — not an error. The backend closes the WebSocket
     * immediately after sending this message.
     */
    data class SessionTimeout(val message: String) : VoiceClientEvent
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

    fun connect(
        conversationId: String,
        voiceLanguage: String? = null,
        tailscaleHost: String? = null,
    ) {
        val url = buildString {
            append("$backendUrl/ws/voice/$conversationId")
            var hasQuery = false
            if (!voiceLanguage.isNullOrBlank()) {
                append(if (hasQuery) "&" else "?")
                append("voice_language=")
                append(URLEncoder.encode(voiceLanguage, Charsets.UTF_8.name()))
                hasQuery = true
            }
            if (!tailscaleHost.isNullOrBlank()) {
                append(if (hasQuery) "&" else "?")
                append("tailscale_host=")
                append(URLEncoder.encode(tailscaleHost, Charsets.UTF_8.name()))
            }
        }
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

    fun sendLocationResponse(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        locationLabel: String?,
    ) {
        if (!isConnected) return
        val payload = JSONObject()
            .put("type", "location_response")
            .put("latitude", latitude)
            .put("longitude", longitude)
        accuracyMeters?.let { payload.put("accuracy_meters", it.toDouble()) }
        locationLabel?.let { payload.put("location_label", it) }
        webSocket?.send(payload.toString())
    }

    /** Send a JPEG image frame (e.g. from Meta glasses) to Gervis for visual understanding. */
    fun sendImage(jpegBytes: ByteArray, source: String? = null) {
        if (!isConnected) return
        val b64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
        val payload = JSONObject()
            .put("type", "image")
            .put("mime_type", "image/jpeg")
            .put("data", b64)
        if (!source.isNullOrBlank()) {
            payload.put("source", source)
        }
        webSocket?.send(payload.toString())
    }

    /** Send a text chat message into the active live session. */
    fun sendTextInput(text: String) {
        if (!isConnected || text.isBlank()) return
        webSocket?.send(
            JSONObject()
                .put("type", "text_input")
                .put("text", text)
                .toString(),
        )
    }

    fun sendSessionCapabilities(
        glassesCameraReady: Boolean,
        listeningEnabled: Boolean,
    ) {
        if (!isConnected) return
        webSocket?.send(
            JSONObject()
                .put("type", "session_capabilities")
                .put("glasses_camera_ready", glassesCameraReady)
                .put("listening_enabled", listeningEnabled)
                .toString(),
        )
    }

    fun sendVisualCaptureFailure(reason: String) {
        if (!isConnected) return
        webSocket?.send(
            JSONObject()
                .put("type", "visual_capture_status")
                .put("status", "failed")
                .put("reason", reason)
                .toString(),
        )
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
            "turn_complete" -> _events.tryEmit(VoiceClientEvent.TurnComplete)
            "input_transcript" -> {
                val text = json.optString("text")
                if (text.isNotBlank()) {
                    _events.tryEmit(
                        VoiceClientEvent.InputTranscript(
                            text = text,
                            isPartial = json.optBoolean("is_partial", false),
                        ),
                    )
                }
            }
            "output_transcript" -> {
                val text = json.optString("text")
                if (text.isNotBlank()) {
                    _events.tryEmit(
                        VoiceClientEvent.OutputTranscript(
                            text = text,
                            isPartial = json.optBoolean("is_partial", false),
                        ),
                    )
                }
            }
            "tool_status" -> {
                val activityId = json.optString("activity_id")
                val label = json.optString("label")
                val status = json.optString("status")
                if (activityId.isNotBlank() && label.isNotBlank() && status.isNotBlank()) {
                    _events.tryEmit(
                        VoiceClientEvent.ToolStatus(
                            activityId = activityId,
                            label = label,
                            status = status,
                            durationMs = if (json.has("duration_ms")) {
                                max(0L, json.optLong("duration_ms"))
                            } else {
                                null
                            },
                        )
                    )
                }
            }
            "state_update" -> {
                val state = json.optString("state")
                if (state.isNotBlank()) {
                    val prdSummary = if (state == "awaiting_confirmation") {
                        json.optJSONObject("prd_summary")?.let { PrdSummary.fromJson(it) }
                    } else null
                    _events.tryEmit(VoiceClientEvent.StateUpdate(state, prdSummary))
                }
            }
            "job_started" -> {
                val jobId = json.optString("job_id")
                val description = json.optString("description")
                _events.tryEmit(VoiceClientEvent.JobStarted(jobId, description))
            }
            "job_update" -> {
                val jobId = json.optString("job_id")
                val status = json.optString("status")
                val msg = json.optString("display_summary")
                    .ifBlank { json.optString("message") }
                _events.tryEmit(VoiceClientEvent.JobUpdate(jobId, status, msg))
            }
            "request_visual_capture" -> {
                val source = json.optString("source", "glasses")
                _events.tryEmit(VoiceClientEvent.RequestVisualCapture(source))
            }
            "request_location" -> _events.tryEmit(VoiceClientEvent.LocationRequest)
            "session_timeout" -> {
                val msg = json.optString("message", "Session ended after 10 minutes. Say 'Hey Gervis' to continue.")
                _events.tryEmit(VoiceClientEvent.SessionTimeout(msg))
            }
            "error" -> {
                val msg = json.optString("message", "Unknown backend error.")
                _events.tryEmit(VoiceClientEvent.Error(msg))
            }
            else -> Log.d(TAG, "Unknown control message type: $type")
        }
    }
}
