package com.spectalk.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.max

class PcmAudioPlayer(context: Context) {
    companion object {
        private const val SAMPLE_RATE_HZ = 24_000
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
    }

    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var previousMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    // Counts enqueued-but-not-yet-written chunks.
    // VoiceAgentViewModel uses this to pause inactivity timer while Gervis is speaking.
    private val pendingChunks = AtomicInteger(0)
    val hasPendingAudio: Boolean get() = pendingChunks.get() > 0

    fun start(scope: CoroutineScope, preferSpeaker: Boolean) {
        if (playbackJob != null) return

        configureRoute(preferSpeaker)

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, AUDIO_FORMAT)
        val playbackAttributes = AudioAttributes.Builder()
            // VOICE_COMMUNICATION so the AudioTrack shares the same VoIP audio
            // path as the VOICE_COMMUNICATION AudioRecord.  Hardware AEC needs
            // both endpoints on the same path to use playback as a reference
            // signal — without this, AEC has no reference and echo leaks through.
            // Routing is handled by configureRoute(): MODE_IN_COMMUNICATION +
            // setCommunicationDevice(speaker) forces loudspeaker output when no
            // BT/wearable is connected; clearCommunicationDevice() lets BT route
            // naturally via HFP when glasses/earbuds are active.
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }
            }
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(playbackAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setEncoding(AUDIO_FORMAT)
                    .setChannelMask(CHANNEL_MASK)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBufferSize * 2, 4_096))
            .build()

        track.play()
        audioTrack = track

        playbackJob = scope.launch(Dispatchers.IO) {
            for (chunk in audioQueue) {
                audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                pendingChunks.decrementAndGet()
            }
        }
    }

    fun setPreferSpeaker(preferSpeaker: Boolean) {
        if (playbackJob == null) return
        configureRoute(preferSpeaker)
    }

    fun enqueue(audioBytes: ByteArray) {
        pendingChunks.incrementAndGet()
        audioQueue.trySend(audioBytes)
    }

    fun clear() {
        while (audioQueue.tryReceive().isSuccess) {}
        pendingChunks.set(0)
        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.play()
            }.getOrElse { }
        }
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null

        audioQueue.close()

        audioTrack?.let { track ->
            runCatching {
                track.stop()
                track.flush()
            }.getOrElse { }
            track.release()
        }
        audioTrack = null
        restoreRoute()
    }

    private fun configureRoute(preferSpeaker: Boolean) {
        val manager = audioManager ?: return
        if (previousMode == null) previousMode = manager.mode
        if (previousSpeakerphoneOn == null) previousSpeakerphoneOn = manager.isSpeakerphoneOn

        manager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (preferSpeaker) {
                val speaker = manager.availableCommunicationDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    manager.setCommunicationDevice(speaker)
                } else {
                    manager.clearCommunicationDevice()
                }
            } else {
                manager.clearCommunicationDevice()
            }
        }

        manager.isSpeakerphoneOn = preferSpeaker
    }

    private fun restoreRoute() {
        val manager = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
        }

        previousSpeakerphoneOn?.let { manager.isSpeakerphoneOn = it }
        previousMode?.let { manager.mode = it }
        previousSpeakerphoneOn = null
        previousMode = null
    }
}
