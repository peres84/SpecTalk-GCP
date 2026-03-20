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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import okio.ByteString

// Result of a photo capture attempt
sealed interface CaptureResult {
    data class Success(val jpegBytes: ByteArray) : CaptureResult
    data class Failure(val reason: String) : CaptureResult
}

object GlassesCameraManager {
    private const val TAG = "GlassesCameraManager"

    private var session: StreamSession? = null
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // Call once when a voice session starts and glasses are connected.
    fun startSession(context: Context) {
        if (session != null) return
        val newSession = Wearables.startStreamSession(
            context = context,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = StreamConfiguration(
                videoQuality = VideoQuality.LOW,  // 360x640 — best quality over BT
                frameRate = 2,                    // minimal frames; we only need stills
            ),
        )
        session = newSession
        Log.i(TAG, "StreamSession started")
    }

    // Observe the stream state and update isReady.
    // Call this in a coroutine scope after startSession().
    suspend fun observeStreamState() {
        session?.state?.collect { state ->
            _isReady.value = state == StreamSessionState.STREAMING
            Log.d(TAG, "StreamSession state: $state")
        }
    }

    // Capture a still photo from the DAT SDK rather than trying to convert
    // an arbitrary streamed frame into a JPEG ourselves.
    suspend fun capturePhoto(): CaptureResult {
        val s = session ?: return CaptureResult.Failure("No active stream session")
        if (!_isReady.value) return CaptureResult.Failure("Stream not ready")
        return try {
            s.capturePhoto().fold(
                onSuccess = { photoData ->
                    val bytes = photoData.toByteArray()
                    if (bytes.size == 0) {
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
            Log.w(TAG, "capturePhoto failed: ${e.message}")
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
            "Unsupported Meta photo data type: ${raw::class.java.name}"
        )
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    fun stopSession() {
        session?.close()  // close() is the lifecycle-ending call; there is no stop()
        session = null
        _isReady.value = false
        Log.i(TAG, "StreamSession stopped")
    }
}
