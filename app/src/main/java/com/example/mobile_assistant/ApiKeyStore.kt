package com.example.mobile_assistant

import android.content.Context

internal object ApiKeyStore {
    private const val PREFS_NAME = "assistant_prefs"
    private const val KEY_ANTHROPIC = "anthropic_api_key"

    fun getAnthropicKey(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ANTHROPIC, "").orEmpty().trim()

    fun setAnthropicKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANTHROPIC, key.trim())
            .apply()
    }

    fun hasAnthropicKey(context: Context): Boolean = getAnthropicKey(context).isNotBlank()
}
