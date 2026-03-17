package com.spectalk.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class AndroidAudioRecorder {
    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_BYTES = 2_048
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    val isRunning: Boolean
        get() = recordingJob?.isActive == true

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope, onAudioChunk: (ByteArray) -> Unit): Boolean {
        if (isRunning) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) return false

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            max(minBufferSize * 2, CHUNK_SIZE_BYTES),
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        recorder.startRecording()
        audioRecord = recorder
        attachAudioEffects(recorder.audioSessionId)

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(max(minBufferSize, CHUNK_SIZE_BYTES))
            while (isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onAudioChunk(buffer.copyOf(read))
                }
            }
        }

        return true
    }

    fun stop() {
        recordingJob?.cancel()
        recordingJob = null
        releaseAudioEffects()

        audioRecord?.let { recorder ->
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }.getOrElse { }
            recorder.release()
        }
        audioRecord = null
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        acousticEchoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        } else null

        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        } else null

        automaticGainControl = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        } else null
    }

    private fun releaseAudioEffects() {
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        automaticGainControl?.release()
        automaticGainControl = null
    }
}
