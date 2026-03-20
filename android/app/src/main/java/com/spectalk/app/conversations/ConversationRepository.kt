package com.spectalk.app.conversations

import com.spectalk.app.config.BackendConfig
import com.spectalk.app.voice.ConversationTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ConversationRepository(private val http: OkHttpClient = defaultClient) {

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetch all conversations for the authenticated user, newest first.
     * @throws IOException on network or non-2xx response.
     */
    suspend fun listConversations(jwt: String): List<ConversationItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/conversations")
            .get()
            .header("Authorization", "Bearer $jwt")
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to load conversations (${response.code})")
        }

        val body = response.body?.string() ?: return@withContext emptyList()
        val array = JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    ConversationItem(
                        id = obj.getString("conversation_id"),
                        state = obj.getString("state"),
                        lastTurnSummary = obj.optString("last_turn_summary")
                            .takeIf { it.isNotBlank() && it != "null" },
                        pendingResumeCount = obj.optInt("pending_resume_count", 0),
                        createdAt = obj.getString("created_at"),
                        updatedAt = obj.getString("updated_at"),
                    )
                )
            }
        }
    }

    /**
     * Delete a conversation. Returns true on success (204), false otherwise.
     */
    suspend fun deleteConversation(jwt: String, conversationId: String): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BackendConfig.baseUrl}/conversations/$conversationId")
                .delete()
                .header("Authorization", "Bearer $jwt")
                .build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    response.code in 200..299 || response.code == 404
                }
            }.getOrDefault(false)
        }

    /**
     * Update the state of a conversation (e.g. "active" or "idle").
     * Returns true on success (2xx), false otherwise.
     * When setting "active", the backend is expected to deactivate all other conversations
     * for that user so only one active conversation exists at a time.
     */
    suspend fun updateConversationState(jwt: String, conversationId: String, state: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("state", state).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BackendConfig.baseUrl}/conversations/$conversationId")
                .patch(body)
                .header("Authorization", "Bearer $jwt")
                .build()
            runCatching { http.newCall(request).execute().code in 200..299 }.getOrDefault(false)
        }

    /**
     * Acknowledge all pending resume events for a conversation.
     * Called after Gervis delivers the welcome-back message so the badge clears.
     * The endpoint is idempotent — safe to call even if there are no pending events.
     */
    suspend fun ackResumeEvent(jwt: String, conversationId: String): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BackendConfig.baseUrl}/conversations/$conversationId/ack-resume-event")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $jwt")
                .build()
            runCatching { http.newCall(request).execute().code in 200..299 }.getOrDefault(false)
        }

    /**
     * Submit a PRD confirmation or change request.
     * POST /conversations/{conversationId}/confirm
     *   Body: {"confirmed": true}
     *      or {"confirmed": false, "change_request": "make it mobile"}
     * Returns true on 2xx, false on any error or non-2xx (including 404 = no pending PRD).
     */
    suspend fun confirmPrd(
        jwt: String,
        conversationId: String,
        confirmed: Boolean,
        changeRequest: String?,
        networkHost: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("confirmed", confirmed)
            if (!changeRequest.isNullOrBlank()) put("change_request", changeRequest)
            if (!networkHost.isNullOrBlank()) put("network_host", networkHost)
        }
        val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/conversations/$conversationId/confirm")
            .post(body)
            .header("Authorization", "Bearer $jwt")
            .build()
        runCatching { http.newCall(request).execute().code in 200..299 }.getOrDefault(false)
    }

    /**
     * Fetch turn history for a conversation, oldest-first (natural chat order).
     * Returns an empty list on any error so callers can proceed without history.
     */
    suspend fun fetchTurns(jwt: String, conversationId: String): List<ConversationTurn> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BackendConfig.baseUrl}/conversations/$conversationId/turns?limit=100")
                .get()
                .header("Authorization", "Bearer $jwt")
                .build()

            val response = runCatching { http.newCall(request).execute() }.getOrNull()
                ?: return@withContext emptyList()

            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val array = runCatching { JSONArray(body) }.getOrNull()
                ?: return@withContext emptyList()

            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val role = obj.optString("role").takeIf { it == "user" || it == "assistant" }
                        ?: continue
                    val text = obj.optString("text").takeIf { it.isNotBlank() } ?: continue
                    add(ConversationTurn(role = role, text = text))
                }
            }
        }
}
