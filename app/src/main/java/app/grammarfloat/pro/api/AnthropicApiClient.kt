package app.grammarfloat.pro.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object AnthropicApiClient : ApiClient {

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
            model = "claude-haiku-4-5-20251001",
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

        NetworkModule.okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Anthropic API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body.string()
            val anthropicResponse = NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
            
            anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw Exception("No content in response")
        }
    }

    override suspend fun explainCorrection(original: String, corrected: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val prompt = "Briefly explain in 1-2 sentences why the following text was corrected.\n\nOriginal: $original\nCorrected: $corrected"

        val payload = AnthropicRequest(
            model = "claude-haiku-4-5-20251001",
            max_tokens = 300,
            system = "You are a helpful grammar assistant. Provide short, concise explanations.",
            messages = listOf(
                Message(role = "user", content = prompt)
            )
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey.trim())
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Anthropic API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body.string()
            val anthropicResponse = NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
            
            anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw Exception("No content in response")
        }
    }

    override suspend fun adjustTone(text: String, tone: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val instruction = when (tone.lowercase()) {
            "professional" -> "Rewrite the following text to sound highly professional, formal, and polished. Respond ONLY with the rewritten text. Do not add conversational padding, explanations, or quotes."
            "casual" -> "Rewrite the following text to sound casual, friendly, and conversational. Respond ONLY with the rewritten text. Do not add conversational padding, explanations, or quotes."
            "shorten" -> "Rewrite the following text to be as concise and short as possible without losing the main meaning. Respond ONLY with the rewritten text. Do not add conversational padding, explanations, or quotes."
            else -> "Rewrite the following text. Respond ONLY with the rewritten text. Do not add conversational padding, explanations, or quotes."
        }

        val payload = AnthropicRequest(
            model = "claude-haiku-4-5-20251001",
            max_tokens = 1024,
            system = instruction,
            messages = listOf(
                Message(role = "user", content = text)
            )
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey.trim())
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Anthropic API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body.string()
            val anthropicResponse = NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
            
            anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw Exception("No content in response")
        }
    }
}
