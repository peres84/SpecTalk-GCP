package com.spectalk.app.settings

import android.content.Context
import androidx.core.content.edit

object AppPreferences {
    const val PREFS_NAME = "spectalk_prefs"
    const val PREF_WAKE_WORD = "pref_wake_word"
    const val PREF_SHARE_LOCATION = "pref_share_location"

    const val DEFAULT_WAKE_WORD = "Hey Gervis"

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

    fun isLocationSharingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_SHARE_LOCATION, false)

    fun setLocationSharingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(PREF_SHARE_LOCATION, enabled) }
    }
}
