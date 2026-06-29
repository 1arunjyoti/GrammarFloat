package app.grammarfloat.pro.api

import android.content.Context
import app.grammarfloat.pro.storage.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GrammarRepository(private val context: Context) {

    private val apiKeyStore = ApiKeyStore(context)

    companion object {
        private const val TAG = "GrammarRepository"
    }

    private data class ClientConfig(
        val apiClient: ApiClient,
        val apiKey: String,
        val modelId: String,
        val providerName: String
    )

    suspend fun checkGrammar(text: String): String = withContext(Dispatchers.IO) {
        val config = getClientConfig()
        android.util.Log.d(TAG, "checkGrammar → provider=${config.providerName}, model=${config.modelId}")
        config.apiClient.checkGrammar(text, config.apiKey, config.modelId)
    }

    suspend fun explainCorrection(original: String, corrected: String): String = withContext(Dispatchers.IO) {
        val config = getClientConfig()
        android.util.Log.d(TAG, "explainCorrection → provider=${config.providerName}, model=${config.modelId}")
        config.apiClient.explainCorrection(original, corrected, config.apiKey, config.modelId)
    }

    suspend fun adjustTone(text: String, tone: String): String = withContext(Dispatchers.IO) {
        val config = getClientConfig()
        android.util.Log.d(TAG, "adjustTone → provider=${config.providerName}, model=${config.modelId}")
        config.apiClient.adjustTone(text, tone, config.apiKey, config.modelId)
    }
    
    @Volatile
    private var cachedProvider: Provider? = null
    @Volatile
    private var cachedApiKey: String? = null
    @Volatile
    private var cachedModelId: String? = null
    @Volatile
    private var cachedApiClient: ApiClient? = null

    @Synchronized
    private fun getClientConfig(): ClientConfig {
        val provider = apiKeyStore.getActiveProvider()
        val modelId = apiKeyStore.getModelId(provider) ?: ModelDefaults.forProvider(provider)
        
        // Return cached values if provider and model haven't changed and key exists
        if (provider == cachedProvider
            && modelId == cachedModelId
            && !cachedApiKey.isNullOrBlank()
            && cachedApiClient != null) {
            return ClientConfig(cachedApiClient!!, cachedApiKey!!, cachedModelId!!, cachedProvider!!.name)
        }
        
        val apiKey = apiKeyStore.getApiKey(provider)
        if (apiKey.isNullOrBlank()) {
            cachedProvider = null
            cachedApiKey = null
            cachedModelId = null
            cachedApiClient = null
            throw MissingApiKeyException("API key missing. Please configure in settings.")
        }
        
        cachedProvider = provider
        cachedApiKey = apiKey
        cachedModelId = modelId
        cachedApiClient = ApiClientFactory.get(provider)
        android.util.Log.i(TAG, "Config loaded → provider=${provider.name}, model=$modelId")
        return ClientConfig(cachedApiClient!!, apiKey, modelId, provider.name)
    }
}

class MissingApiKeyException(message: String) : Exception(message)
