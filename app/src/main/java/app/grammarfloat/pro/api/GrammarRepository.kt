package app.grammarfloat.pro.api

import android.content.Context
import app.grammarfloat.pro.storage.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GrammarRepository(private val context: Context) {
    
    private val apiKeyStore = ApiKeyStore(context)

    suspend fun checkGrammar(text: String): String = withContext(Dispatchers.IO) {
        val (apiClient, apiKey) = getClientAndKey()
        apiClient.checkGrammar(text, apiKey)
    }

    suspend fun explainCorrection(original: String, corrected: String): String = withContext(Dispatchers.IO) {
        val (apiClient, apiKey) = getClientAndKey()
        apiClient.explainCorrection(original, corrected, apiKey)
    }

    suspend fun adjustTone(text: String, tone: String): String = withContext(Dispatchers.IO) {
        val (apiClient, apiKey) = getClientAndKey()
        apiClient.adjustTone(text, tone, apiKey)
    }
    
    @Volatile
    private var cachedProvider: Provider? = null
    @Volatile
    private var cachedApiKey: String? = null
    @Volatile
    private var cachedApiClient: ApiClient? = null

    @Synchronized
    private fun getClientAndKey(): Pair<ApiClient, String> {
        val provider = apiKeyStore.getActiveProvider()
        
        // Return cached key and client if provider hasn't changed and key exists
        if (provider == cachedProvider && !cachedApiKey.isNullOrBlank() && cachedApiClient != null) {
            return Pair(cachedApiClient!!, cachedApiKey!!)
        }
        
        val apiKey = apiKeyStore.getApiKey(provider)
        if (apiKey.isNullOrBlank()) {
            cachedProvider = null
            cachedApiKey = null
            cachedApiClient = null
            throw MissingApiKeyException("API key missing. Please configure in settings.")
        }
        
        cachedProvider = provider
        cachedApiKey = apiKey
        cachedApiClient = ApiClientFactory.get(provider)
        return Pair(cachedApiClient!!, apiKey)
    }
}

class MissingApiKeyException(message: String) : Exception(message)
