package com.spectalk.app.device

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream

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

    // Grab the next frame from the video stream and compress to JPEG.
    // VideoFrame.buffer contains decoded pixel data; we create an ARGB_8888 Bitmap from it.
    suspend fun capturePhoto(): CaptureResult {
        val s = session ?: return CaptureResult.Failure("No active stream session")
        if (!_isReady.value) return CaptureResult.Failure("Stream not ready")
        return try {
            val frame = s.videoStream.first()
            frame.buffer.rewind()
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(frame.buffer)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            CaptureResult.Success(out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "capturePhoto failed: ${e.message}")
            CaptureResult.Failure(e.message ?: "Capture failed")
        }
    }

    fun stopSession() {
        session?.close()  // close() is the lifecycle-ending call; there is no stop()
        session = null
        _isReady.value = false
        Log.i(TAG, "StreamSession stopped")
    }
}
