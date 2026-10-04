package com.flowvoice.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_GROQ) ?: PROVIDER_GROQ
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var tone: String
        get() = prefs.getString(KEY_TONE, TONE_NATURAL) ?: TONE_NATURAL
        set(value) = prefs.edit().putString(KEY_TONE, value).apply()

    var autoInsert: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INSERT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_INSERT, value).apply()

    companion object {
        private const val PREF_NAME = "flow_voice_prefs"
        private const val KEY_PROVIDER = "key_provider"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_TONE = "key_tone"
        private const val KEY_AUTO_INSERT = "key_auto_insert"

        const val PROVIDER_GROQ = "GROQ"
        const val PROVIDER_SARVAM = "SARVAM"
        const val PROVIDER_OPENAI = "OPENAI"

        const val TONE_NATURAL = "Natural & Fluent"
        const val TONE_PROFESSIONAL = "Professional / Work"
        const val TONE_CASUAL = "Casual / Chat"
        const val TONE_CONCISE = "Concise & Direct"
    }
}
