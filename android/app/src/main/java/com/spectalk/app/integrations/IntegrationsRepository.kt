package com.spectalk.app.integrations

import com.spectalk.app.config.BackendConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class IntegrationItem(
    val service: String,
    val urlPreview: String,
    val connected: Boolean,
)

sealed class SaveResult {
    data class Success(val urlPreview: String, val message: String) : SaveResult()
    data class Error(val message: String) : SaveResult()
}

class IntegrationsRepository(private val http: OkHttpClient = defaultClient) {

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getIntegrations(jwt: String): List<IntegrationItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/integrations")
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
                add(
                    IntegrationItem(
                        service = obj.getString("service"),
                        urlPreview = obj.optString("url_preview"),
                        connected = obj.optBoolean("connected", false),
                    )
                )
            }
        }
    }

    suspend fun saveIntegration(
        jwt: String,
        service: String,
        url: String,
        token: String,
    ): SaveResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("service", service)
            .put("url", url)
            .put("token", token)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${BackendConfig.baseUrl}/integrations")
            .post(body)
            .header("Authorization", "Bearer $jwt")
            .build()

        runCatching {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                return@runCatching SaveResult.Error("Server error (${response.code})")
            }
            val json = JSONObject(response.body!!.string())
            SaveResult.Success(
                urlPreview = json.optString("url_preview"),
                message = json.optString("message", "Saved successfully"),
            )
        }.getOrElse { e -> SaveResult.Error(e.message ?: "Network error") }
    }

    suspend fun deleteIntegration(jwt: String, service: String): Boolean =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BackendConfig.baseUrl}/integrations/$service")
                .delete()
                .header("Authorization", "Bearer $jwt")
                .build()
            runCatching { http.newCall(request).execute().code in 200..299 }.getOrDefault(false)
        }
}
