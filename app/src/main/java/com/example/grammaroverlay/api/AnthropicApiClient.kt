package com.example.grammaroverlay.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AnthropicApiClient : ApiClient {

    @Serializable
    private data class AnthropicRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<Message>
    )

    @Serializable
    private data class Message(
        val role: String,
        val content: String
    )

    @Serializable
    private data class AnthropicResponse(
        val content: List<ContentBlock>? = null
    )

    @Serializable
    private data class ContentBlock(
        val text: String
    )

    override suspend fun checkGrammar(text: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val payload = AnthropicRequest(
            model = "claude-3-haiku-20240307",
            max_tokens = 1024,
            system = "You are a professional grammar and spell checker. Respond ONLY with the corrected text. Do not add conversational padding, explanations, or quotes. If the text is already perfect, return it exactly as is.",
            messages = listOf(Message("user", text))
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey.trim())
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Anthropic API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body?.string() ?: throw Exception("Empty response body")
            val anthropicResponse = NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
            
            anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw Exception("No content in response")
        }
    }
}
