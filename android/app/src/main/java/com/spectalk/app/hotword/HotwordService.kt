package com.spectalk.app.hotword

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.spectalk.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * The service self-manages its lifecycle based on Bluetooth headset state:
 *  - When a BT headset (Meta glasses, AirPods, etc.) is connected, Vosk listens.
 *  - When BT disconnects, Vosk stops and the service calls stopSelf().
 *
 * While a voice session is active, HotwordEventBus.isPaused is true and the
 * Vosk recogniser is stopped so the microphone is free. It resumes
 * automatically when the session ends.
 *
 * ── Setup ────────────────────────────────────────────────────────────────────
 * Download the small English model and place it in the app assets:
 *   https://alphacephei.com/vosk/models → vosk-model-small-en-us-0.15.zip
 * Extract and rename the folder to "model", then put it at:
 *   app/src/main/assets/model/
 * ─────────────────────────────────────────────────────────────────────────────
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

        const val PREF_NAME = "spectalk_prefs"
        const val PREF_WAKE_WORD = "pref_wake_word"
        const val DEFAULT_WAKE_WORD = "hey gervis"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var model: Model? = null
    private var speechService: SpeechService? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var btReceiver: BroadcastReceiver? = null
    @Volatile private var isBtHeadsetConnected = false

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotwordService::cpu")
            .also { it.acquire() }

        // BLUETOOTH_CONNECT is a runtime permission on API 31+. Use runCatching so a
        // missing permission degrades gracefully — BT state tracking just won't work.
        isBtHeadsetConnected = runCatching {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btManager?.adapter
                ?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val connected = state == BluetoothProfile.STATE_CONNECTED
                isBtHeadsetConnected = connected
                if (connected) {
                    Log.i(TAG, "BT headset connected — restarting Vosk listener.")
                    serviceScope.launch {
                        stopListening()
                        delay(1_200)
                        if (!HotwordEventBus.isPaused.value && model != null) startListening()
                    }
                } else {
                    // BT disconnected — keep service running so phone mic takes over
                    Log.i(TAG, "BT headset disconnected — continuing on phone mic.")
                    serviceScope.launch {
                        stopListening()
                        delay(500)
                        if (!HotwordEventBus.isPaused.value && model != null) startListening()
                    }
                }
            }
        }
        btReceiver = receiver
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }

        serviceScope.launch {
            HotwordEventBus.isPaused.collect { paused ->
                if (paused) {
                    stopListening()
                } else if (model != null) {
                    startListening()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (model != null) {
            serviceScope.launch {
                stopListening()
                delay(300)
                if (!HotwordEventBus.isPaused.value) startListening()
            }
        } else {
            serviceScope.launch { initVosk() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        btReceiver?.let { unregisterReceiver(it) }
        btReceiver = null
        stopListening()
        model?.close()
        model = null
        serviceJob.cancel()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Vosk init ────────────────────────────────────────────────────────────

    private suspend fun initVosk() = withContext(Dispatchers.IO) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val modelDir = File(filesDir, MODEL_ASSET)
            if (!modelDir.exists()) {
                Log.i(TAG, "Copying Vosk model from assets (first run)…")
                copyAssets(MODEL_ASSET, modelDir)
            }
            model = Model(modelDir.absolutePath)
            Log.i(TAG, "Vosk model loaded.")
            if (!HotwordEventBus.isPaused.value) startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialise Vosk: ${e.message}")
        }
    }

    // ── Recognition control ──────────────────────────────────────────────────

    private fun startListening() {
        speechService?.stop()
        val m = model ?: return

        // Read the current configured wake word from SharedPreferences each time
        // so any change in Settings takes effect on the next listen cycle.
        val prefs: SharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val configuredWord = prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
            ?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_WAKE_WORD

        // Build a compact grammar: the full phrase + just the name + unknown
        val shortName = configuredWord.removePrefix("hey ").trim()
        val grammar = """["$configuredWord", "$shortName", "[unk]"]"""

        try {
            val recognizer = Recognizer(m, SAMPLE_RATE, grammar)
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

    // ── RecognitionListener ──────────────────────────────────────────────────

    override fun onResult(hypothesis: String?) {
        hypothesis ?: return
        try {
            val text = JSONObject(hypothesis).optString("text", "").trim()
            if (text.isNotEmpty() && text != "[unk]" && isWakePhrase(text)) {
                Log.i(TAG, "Wake word detected: \"$text\"")
                HotwordEventBus.notifyWakeWord()
                postWakeNotification()
            }
        } catch (_: JSONException) {}
    }

    override fun onPartialResult(hypothesis: String?) {
        hypothesis ?: return
        try {
            val text = JSONObject(hypothesis).optString("partial", "").trim()
            if (text.isNotEmpty() && isWakePhrase(text)) {
                Log.i(TAG, "Wake word detected (partial): \"$text\"")
                HotwordEventBus.notifyWakeWord()
                postWakeNotification()
            }
        } catch (_: JSONException) {}
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        Log.w(TAG, "Vosk error: ${e?.message}")
        serviceScope.launch {
            delay(1_500)
            if (!HotwordEventBus.isPaused.value && model != null) {
                startListening()
            }
        }
    }

    override fun onTimeout() {
        if (!HotwordEventBus.isPaused.value) startListening()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun isWakePhrase(text: String): Boolean {
        val prefs: SharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val configuredWord = prefs.getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
            ?.trim()?.lowercase() ?: DEFAULT_WAKE_WORD
        val shortName = configuredWord.removePrefix("hey ").trim()
        return text.contains(configuredWord) || text == shortName
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Listening for \"Hey Gervis\"" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpecTalk")
            .setContentText("Listening for \"Hey Gervis\"…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setSilent(true)
            .setOngoing(true)
            .build()
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
            this, 0, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, WAKE_CHANNEL_ID)
            .setContentTitle("Gervis")
            .setContentText("Listening…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .build()

        nm.notify(WAKE_NOTIFICATION_ID, notification)
    }

    // ── Asset copy ───────────────────────────────────────────────────────────

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
