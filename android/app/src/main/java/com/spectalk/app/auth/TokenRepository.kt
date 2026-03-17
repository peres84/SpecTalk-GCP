package com.spectalk.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.spectalk.app.config.BackendConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages the product JWT lifecycle:
 *  - persists it in [EncryptedSharedPreferences]
 *  - exchanges a Firebase ID token for a product JWT via POST /auth/session
 *  - provides helpers for other authenticated backend calls made before
 *    a full API client layer is introduced
 */
class TokenRepository(context: Context) {

    companion object {
        private const val PREFS_FILE = "spectalk_secure_prefs"
        private const val KEY_JWT = "product_jwt"
        private const val KEY_USER_ID = "user_id"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Auth ─────────────────────────────────────────────────────────────────

    /**
     * Exchange a Firebase ID token for a product JWT.
     * Stores the JWT and user_id in encrypted storage on success.
     * @throws IOException on network or non-2xx response.
     */
    suspend fun exchangeFirebaseToken(idToken: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("firebase_id_token", idToken).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/auth/session")
            .post(body)
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            val detail = runCatching { response.body?.string() }.getOrNull()
            throw IOException("Backend auth failed (${response.code}): $detail")
        }

        val json = JSONObject(response.body!!.string())
        val jwt = json.getString("access_token")
        val userId = json.getString("user_id")

        prefs.edit()
            .putString(KEY_JWT, jwt)
            .putString(KEY_USER_ID, userId)
            .apply()

        jwt
    }

    fun getProductJwt(): String = prefs.getString(KEY_JWT, "") ?: ""

    fun getUserId(): String = prefs.getString(KEY_USER_ID, "") ?: ""

    fun clearTokens() {
        prefs.edit().remove(KEY_JWT).remove(KEY_USER_ID).apply()
    }

    // ── Voice session ────────────────────────────────────────────────────────

    /**
     * Call POST /voice/session/start to create a new conversation on the backend.
     * Returns the conversation_id to pass to the WebSocket.
     * @throws IOException on failure.
     */
    suspend fun startVoiceSession(): String = withContext(Dispatchers.IO) {
        val jwt = getProductJwt().ifBlank { throw IOException("No product JWT — user not authenticated") }

        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/voice/session/start")
            .post("".toRequestBody(null))
            .header("Authorization", "Bearer $jwt")
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to start voice session (${response.code})")
        }

        JSONObject(response.body!!.string()).getString("conversation_id")
    }

    // ── Notifications ────────────────────────────────────────────────────────

    /**
     * Register an FCM push token with the backend.
     * Best-effort — failures are silently ignored by the caller.
     */
    suspend fun registerPushToken(fcmToken: String) = withContext(Dispatchers.IO) {
        val jwt = getProductJwt().ifBlank { return@withContext }

        val body = JSONObject().put("push_token", fcmToken).toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/notifications/device/register")
            .post(body)
            .header("Authorization", "Bearer $jwt")
            .build()

        runCatching { http.newCall(request).execute() }
    }
}
