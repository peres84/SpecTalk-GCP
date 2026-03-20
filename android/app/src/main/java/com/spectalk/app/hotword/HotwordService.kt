package com.spectalk.app.hotword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spectalk.app.MainActivity
import com.spectalk.app.device.ConnectedDeviceMonitor
import com.spectalk.app.device.ConnectedDeviceState
import com.spectalk.app.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException

/**
 * Foreground service that runs Vosk in constrained grammar mode to listen for
 * the user-configured wake word (default: "Hey Gervis").
 *
 * The wake word is read from SharedPreferences on every start so users can
 * change it in Settings without restarting the app. The Vosk grammar is
 * rebuilt at runtime with the current configured word.
 *
 * Vosk only listens when an allowed external device is connected. That gate is
 * driven by [ConnectedDeviceMonitor], which combines Meta wearable availability
 * and Bluetooth audio-device presence.
 */
class HotwordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "HotwordService"
        private const val SAMPLE_RATE = 16000f
        private const val MODEL_ASSET = "model"
        private const val CHANNEL_ID = "hotword_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_CHANNEL_ID = "hotword_wake_channel"
        private const val WAKE_NOTIFICATION_ID = 1002

        const val PREF_NAME = AppPreferences.PREFS_NAME
        const val PREF_WAKE_WORD = AppPreferences.PREF_WAKE_WORD
        const val DEFAULT_WAKE_WORD = "hey gervis"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var deviceState: ConnectedDeviceState = ConnectedDeviceState()

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotwordService::cpu")
            .also { it.acquire() }

        ConnectedDeviceMonitor.start(this)

        serviceScope.launch {
            HotwordEventBus.isPaused.collect {
                updateListeningState()
                refreshForegroundNotification()
            }
        }

        serviceScope.launch {
            ConnectedDeviceMonitor.state.collect { state ->
                val wasReady = deviceState.isWakeWordReady
                deviceState = state
                if (state.isWakeWordReady != wasReady) {
                    Log.i(
                        TAG,
                        if (state.isWakeWordReady) {
                            "Allowed device connected; wake word ready."
                        } else {
                            "No allowed device connected; wake word paused."
                        },
                    )
                }
                updateListeningState()
                refreshForegroundNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (model != null) {
            serviceScope.launch { updateListeningState() }
        } else {
            serviceScope.launch { initVosk() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        model?.close()
        model = null
        serviceJob.cancel()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun initVosk() = withContext(Dispatchers.IO) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val modelDir = File(filesDir, MODEL_ASSET)
            if (!modelDir.exists()) {
                Log.i(TAG, "Copying Vosk model from assets (first run)...")
                copyAssets(MODEL_ASSET, modelDir)
            }
            model = Model(modelDir.absolutePath)
            Log.i(TAG, "Vosk model loaded.")
            updateListeningState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise Vosk: ${e.message}")
        }
    }

    private fun startListening() {
        if (!deviceState.isWakeWordReady || HotwordEventBus.isPaused.value) return

        speechService?.stop()
        val loadedModel = model ?: return

        val prefs: SharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val configuredWord = prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
            ?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WAKE_WORD

        // Grammar contains only the full phrase + [unk]. The short name ("gervis")
        // is intentionally omitted — including it causes the model to actively try
        // to match it, increasing false positives on phonetically similar words.
        val grammar = """["$configuredWord", "[unk]"]"""

        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE, grammar)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also {
                it.startListening(this)
            }
            Log.d(TAG, "Hotword listening started with grammar: $grammar")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk listening: ${e.message}")
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    private fun updateListeningState() {
        if (HotwordEventBus.isPaused.value || !deviceState.isWakeWordReady || model == null) {
            stopListening()
            return
        }

        if (speechService == null) {
            startListening()
        }
    }

    override fun onResult(hypothesis: String?) {
        hypothesis ?: return
        if (!deviceState.isWakeWordReady) return
        try {
            val text = JSONObject(hypothesis).optString("text", "").trim()
            // Only act on the committed final result, and only on the full configured
            // phrase (e.g. "hey gervis"). Single-word matches ("[unk]", "gervis" alone)
            // are excluded here — they produce too many false positives because the small
            // Vosk model maps acoustically similar words ("nervous", "service") onto the
            // short name from constrained grammar.
            if (text.isNotEmpty() && text != "[unk]" && isFullWakePhrase(text)) {
                Log.i(TAG, "Wake word detected: \"$text\"")
                HotwordEventBus.notifyWakeWord()
                postWakeNotification()
            }
        } catch (_: JSONException) {
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        // Partial results are intermediate, unstable hypotheses that change continuously
        // as audio arrives. Using them for wake-word decisions causes false positives
        // because the constrained grammar maps similar-sounding phonemes onto the wake
        // word mid-utterance. All triggering happens in onResult (final committed result).
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        Log.w(TAG, "Vosk error: ${e?.message}")
        serviceScope.launch {
            delay(1_500)
            updateListeningState()
        }
    }

    override fun onTimeout() {
        updateListeningState()
    }

    /**
     * Returns true only when [text] contains the full configured wake phrase
     * (e.g. "hey gervis"). Single-word matches are intentionally excluded to
     * reduce false positives — the two-word sequence is acoustically distinctive,
     * while the short name alone ("gervis") collides with common phonemes.
     */
    private fun isFullWakePhrase(text: String): Boolean {
        val prefs: SharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val configuredWord = prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
            ?.trim()?.lowercase() ?: DEFAULT_WAKE_WORD
        return text.contains(configuredWord)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Wake word readiness and listening state"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val wakeWord = AppPreferences.getWakeWord(this)
        val contentText = if (deviceState.isWakeWordReady) {
            "Listening for \"$wakeWord\"..."
        } else {
            "Connect Meta glasses or a Bluetooth audio device to enable wake word"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpecTalk")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    private fun refreshForegroundNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun postWakeNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WAKE_CHANNEL_ID,
                "Gervis Wake",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Screen wakes when wake word is detected"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
            .setContentTitle("Gervis")
            .setContentText("Listening...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .build()

        nm.notify(WAKE_NOTIFICATION_ID, notification)
    }

    @Throws(IOException::class)
    private fun copyAssets(srcPath: String, dstFile: File) {
        val children = assets.list(srcPath)
        if (children.isNullOrEmpty()) {
            dstFile.parentFile?.mkdirs()
            assets.open(srcPath).use { input ->
                dstFile.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dstFile.mkdirs()
            for (child in children) {
                copyAssets("$srcPath/$child", File(dstFile, child))
            }
        }
    }
}
