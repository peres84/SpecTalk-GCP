package com.spectalk.app.settings

import android.content.Context
import androidx.core.content.edit

object AppPreferences {
    const val PREFS_NAME = "spectalk_prefs"
    const val PREF_WAKE_WORD = "pref_wake_word"
    const val PREF_SHARE_LOCATION = "pref_share_location"
    const val PREF_AUTO_OPEN_ON_NOTIFICATION = "pref_auto_open_on_notification"
    const val PREF_AGENT_VOICE_LANGUAGE = "pref_agent_voice_language"

    const val DEFAULT_WAKE_WORD = "Hey Gervis"

    enum class AgentVoiceLanguage(
        val prefValue: String,
        val label: String,
        val subtitle: String,
    ) {
        ENGLISH_US(
            prefValue = "en-US",
            label = "English (US)",
            subtitle = "Official Gemini Live support",
        ),
        ENGLISH_UK(
            prefValue = "en-GB",
            label = "English (UK)",
            subtitle = "Guided with British phrasing and pronunciation",
        ),
        SPANISH(
            prefValue = "es-US",
            label = "Spanish",
            subtitle = "Uses Gemini Live Spanish voice mode",
        ),
        GERMAN(
            prefValue = "de-DE",
            label = "German",
            subtitle = "Uses Gemini Live German voice mode",
        );

        companion object {
            fun fromPrefValue(value: String?): AgentVoiceLanguage =
                values().firstOrNull { it.prefValue == value } ?: ENGLISH_US
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWakeWord(context: Context): String =
        prefs(context).getString(PREF_WAKE_WORD, DEFAULT_WAKE_WORD)
            ?.trim()
            ?.ifBlank { DEFAULT_WAKE_WORD }
            ?: DEFAULT_WAKE_WORD

    fun setWakeWord(context: Context, wakeWord: String) {
        prefs(context).edit { putString(PREF_WAKE_WORD, wakeWord.trim().ifBlank { DEFAULT_WAKE_WORD }) }
    }

    fun getAgentVoiceLanguage(context: Context): AgentVoiceLanguage =
        AgentVoiceLanguage.fromPrefValue(
            prefs(context).getString(
                PREF_AGENT_VOICE_LANGUAGE,
                AgentVoiceLanguage.ENGLISH_US.prefValue,
            ),
        )

    fun setAgentVoiceLanguage(context: Context, language: AgentVoiceLanguage) {
        prefs(context).edit { putString(PREF_AGENT_VOICE_LANGUAGE, language.prefValue) }
    }

    fun isLocationSharingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_SHARE_LOCATION, false)

    fun setLocationSharingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_SHARE_LOCATION, enabled) }
    }

    /**
     * When enabled, incoming FCM job-complete notifications automatically open the
     * conversation and start a voice session so Gervis can speak the result aloud.
     * The notification is still shown so the user can dismiss or tap it manually.
     */
    fun isAutoOpenOnNotification(context: Context): Boolean =
        prefs(context).getBoolean(PREF_AUTO_OPEN_ON_NOTIFICATION, false)

    fun setAutoOpenOnNotification(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_AUTO_OPEN_ON_NOTIFICATION, enabled) }
    }

    private const val PREF_PENDING_AUTO_OPEN_CONVERSATION_ID = "pref_pending_auto_open_conversation_id"

    fun setPendingAutoOpenConversationId(context: Context, conversationId: String) {
        prefs(context).edit { putString(PREF_PENDING_AUTO_OPEN_CONVERSATION_ID, conversationId) }
    }

    fun getPendingAutoOpenConversationId(context: Context): String? =
        prefs(context).getString(PREF_PENDING_AUTO_OPEN_CONVERSATION_ID, null)

    fun clearPendingAutoOpenConversationId(context: Context) {
        prefs(context).edit { remove(PREF_PENDING_AUTO_OPEN_CONVERSATION_ID) }
    }
}
