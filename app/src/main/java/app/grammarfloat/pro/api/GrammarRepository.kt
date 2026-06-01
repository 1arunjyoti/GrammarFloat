package app.grammarfloat.pro.api

import android.content.Context
import app.grammarfloat.pro.storage.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GrammarRepository(private val context: Context) {
    
    private val apiKeyStore = ApiKeyStore(context)

    suspend fun checkGrammar(text: String): String = withContext(Dispatchers.IO) {
        val (provider, apiKey) = getCredentials()
        val apiClient = ApiClientFactory.create(provider)
        apiClient.checkGrammar(text, apiKey)
    }

    suspend fun explainCorrection(original: String, corrected: String): String = withContext(Dispatchers.IO) {
        val (provider, apiKey) = getCredentials()
        val apiClient = ApiClientFactory.create(provider)
        apiClient.explainCorrection(original, corrected, apiKey)
    }

    suspend fun adjustTone(text: String, tone: String): String = withContext(Dispatchers.IO) {
        val (provider, apiKey) = getCredentials()
        val apiClient = ApiClientFactory.create(provider)
        apiClient.adjustTone(text, tone, apiKey)
    }
    
    private var cachedProvider: Provider? = null
    private var cachedApiKey: String? = null

    private fun getCredentials(): Pair<Provider, String> {
        val provider = apiKeyStore.getActiveProvider()
        
        // Return cached key if provider hasn't changed and key exists
        if (provider == cachedProvider && !cachedApiKey.isNullOrBlank()) {
            return Pair(provider, cachedApiKey!!)
        }
        
        val apiKey = apiKeyStore.getApiKey(provider)
        if (apiKey.isNullOrBlank()) {
            cachedProvider = null
            cachedApiKey = null
            throw MissingApiKeyException("API key missing. Please configure in settings.")
        }
        
        cachedProvider = provider
        cachedApiKey = apiKey
        return Pair(provider, apiKey)
    }
}

class MissingApiKeyException(message: String) : Exception(message)
