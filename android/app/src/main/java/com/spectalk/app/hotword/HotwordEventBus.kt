package com.spectalk.app.hotword

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus for wake word lifecycle.
 *
 * - [wakeWordDetected] emits once whenever "Hey Gervis" (or user-configured word) is recognised.
 * - [isPaused] controls whether HotwordService keeps the Vosk recogniser running.
 *   Set to true while a voice session is active (mic is in use). Reverts to false on disconnect.
 * - [pendingWakeWord] survives Activity recreation so the ViewModel can pick up
 *   a wake event that fired while it was dead (background / locked screen).
 */
object HotwordEventBus {

    private val _wakeWordDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val wakeWordDetected: SharedFlow<Unit> = _wakeWordDetected.asSharedFlow()

    /** true = hotword detector is paused (voice session is active). */
    val isPaused = MutableStateFlow(false)

    /** Sticky flag — set when the wake word fires, cleared by the ViewModel. */
    private val pendingWakeWord = AtomicBoolean(false)

    /** Called by HotwordService when the wake phrase is detected. */
    fun notifyWakeWord() {
        isPaused.value = true   // stop re-triggering while voice session handles the request
        pendingWakeWord.set(true)
        _wakeWordDetected.tryEmit(Unit)
    }

    /**
     * Returns true exactly once if a wake word fired while no ViewModel was
     * collecting [wakeWordDetected].
     */
    fun consumePendingWakeWord(): Boolean = pendingWakeWord.getAndSet(false)

    /** Called by VoiceAgentViewModel when the voice session ends. */
    fun resume() {
        isPaused.value = false
    }
}
