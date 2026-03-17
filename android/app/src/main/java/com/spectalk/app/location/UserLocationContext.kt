package com.spectalk.app.location

import org.json.JSONObject

data class UserLocationContext(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val locationLabel: String? = null,
    val capturedAt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("captured_at", capturedAt)
        .apply {
            accuracyMeters?.let { put("accuracy_meters", it.toDouble()) }
            locationLabel?.let { put("location_label", it) }
        }
}
