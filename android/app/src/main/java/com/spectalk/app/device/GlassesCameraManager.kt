package com.spectalk.app.device

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString

sealed interface CaptureResult {
    data class Success(val jpegBytes: ByteArray) : CaptureResult
    data class Failure(val reason: String) : CaptureResult
}

object GlassesCameraManager {
    private const val TAG = "GlassesCameraManager"
    private const val READY_TIMEOUT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: StreamSession? = null
    private var stateJob: Job? = null
    private var warmStreamJob: Job? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    fun startSession(context: Context) {
        if (session != null) return
        val newSession = Wearables.startStreamSession(
            context = context,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24,
            ),
        )
        session = newSession
        Log.i(TAG, "StreamSession started")

        stateJob?.cancel()
        stateJob = scope.launch {
            newSession.state.collect { state ->
                _isReady.value = state == StreamSessionState.STREAMING
                Log.d(TAG, "StreamSession state: $state")
            }
        }

        warmStreamJob?.cancel()
        warmStreamJob = scope.launch {
            runCatching {
                newSession.videoStream.collect { _ -> }
            }.onFailure { e ->
                Log.w(TAG, "Video stream collector stopped: ${e.message}", e)
            }
        }
    }

    suspend fun capturePhoto(): CaptureResult {
        val activeSession = session ?: return CaptureResult.Failure("No active stream session")
        if (!_isReady.value) {
            Log.i(TAG, "capturePhoto waiting for stream readiness")
            val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
                isReady.filter { it }.first()
            } != null
            if (!ready) {
                return CaptureResult.Failure("Stream not ready")
            }
        }
        return try {
            activeSession.capturePhoto().fold(
                onSuccess = { photoData ->
                    val bytes = photoData.toByteArray()
                    if (bytes.isEmpty()) {
                        CaptureResult.Failure("Glasses returned an empty photo")
                    } else {
                        Log.i(TAG, "capturePhoto succeeded (${bytes.size} bytes)")
                        CaptureResult.Success(bytes)
                    }
                },
                onFailure = { error, throwable ->
                    val reason = throwable?.message ?: error.toString()
                    Log.w(TAG, "capturePhoto failed: $reason", throwable)
                    CaptureResult.Failure(reason)
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "capturePhoto failed: ${e.message}", e)
            CaptureResult.Failure(e.message ?: "Capture failed")
        }
    }

    private fun PhotoData.toByteArray(): ByteArray = when (this) {
        is PhotoData.HEIC -> rawPhotoBytesToByteArray(data)
        is PhotoData.Bitmap -> bitmap.toJpegBytes()
    }

    private fun rawPhotoBytesToByteArray(raw: Any?): ByteArray = when (raw) {
        null -> ByteArray(0)
        is ByteArray -> raw
        is ByteString -> raw.toByteArray()
        is ByteBuffer -> {
            val buffer = raw.slice()
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        else -> throw IllegalStateException(
            "Unsupported Meta photo data type: ${raw::class.java.name}",
        )
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    fun stopSession() {
        stateJob?.cancel()
        stateJob = null
        warmStreamJob?.cancel()
        warmStreamJob = null
        session?.close()
        session = null
        _isReady.value = false
        Log.i(TAG, "StreamSession stopped")
    }
}
