package app.grammarfloat.pro.api

object ApiClientFactory {
    fun get(provider: Provider): ApiClient {
        return when (provider) {
            Provider.ANTHROPIC -> AnthropicApiClient
            Provider.OPENAI -> OpenAiApiClient
            Provider.GEMINI -> GeminiApiClient
        }
    }
}
