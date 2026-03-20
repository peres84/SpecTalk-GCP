package com.spectalk.app.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Project Requirements Document summary received in the awaiting_confirmation state_update.
 * Serialised to SharedPreferences so it survives app backgrounding.
 */
data class PrdSummary(
    val projectName: String,
    val description: String,
    val targetPlatform: String,   // web | mobile | backend | fullstack
    val keyFeatures: List<String>,
    val techStack: String,
    val scopeEstimate: String,    // small | medium | large
) {
    fun toJson(): String {
        val arr = JSONArray().also { a -> keyFeatures.forEach { a.put(it) } }
        return JSONObject()
            .put("project_name", projectName)
            .put("description", description)
            .put("target_platform", targetPlatform)
            .put("key_features", arr)
            .put("tech_stack", techStack)
            .put("scope_estimate", scopeEstimate)
            .toString()
    }

    companion object {
        fun fromJson(json: JSONObject): PrdSummary? = runCatching {
            val features = buildList {
                val arr = json.optJSONArray("key_features")
                if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i))
            }
            PrdSummary(
                projectName = json.optString("project_name", "Untitled Project"),
                description = json.optString("description", ""),
                targetPlatform = json.optString("target_platform", "web"),
                keyFeatures = features,
                techStack = json.optString("tech_stack", ""),
                scopeEstimate = json.optString("scope_estimate", "medium"),
            )
        }.getOrNull()

        fun fromJsonString(raw: String): PrdSummary? = runCatching {
            fromJson(JSONObject(raw))
        }.getOrNull()
    }
}
