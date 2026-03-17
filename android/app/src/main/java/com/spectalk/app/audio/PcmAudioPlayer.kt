package com.spectalk.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.max

class PcmAudioPlayer {
    companion object {
        private const val SAMPLE_RATE_HZ = 24_000
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
    }

    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    // Counts enqueued-but-not-yet-written chunks.
    // VoiceAgentViewModel uses this to pause inactivity timer while Gervis is speaking.
    private val pendingChunks = AtomicInteger(0)
    val hasPendingAudio: Boolean get() = pendingChunks.get() > 0

    fun start(scope: CoroutineScope) {
        if (playbackJob != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, AUDIO_FORMAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
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
    }
}
