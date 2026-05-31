package com.example.grammaroverlay.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.grammaroverlay.api.Provider

class ApiKeyStore(context: Context) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "secret_api_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(provider: Provider): String? {
        return prefs.getString("api_key_${provider.name}", null)
    }

    fun setApiKey(provider: Provider, key: String) {
        prefs.edit().putString("api_key_${provider.name}", key).apply()
    }

    fun clearApiKey(provider: Provider) {
        prefs.edit().remove("api_key_${provider.name}").apply()
    }

    fun getActiveProvider(): Provider {
        val providerName = prefs.getString("active_provider", Provider.ANTHROPIC.name) ?: Provider.ANTHROPIC.name
        return try {
            Provider.valueOf(providerName)
        } catch (e: IllegalArgumentException) {
            Provider.ANTHROPIC
        }
    }

    fun setActiveProvider(provider: Provider) {
        prefs.edit().putString("active_provider", provider.name).apply()
    }
}
