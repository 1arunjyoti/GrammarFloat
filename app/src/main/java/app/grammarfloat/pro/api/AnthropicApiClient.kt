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
        safeApiCall("Anthropic") {
            val payload = AnthropicRequest(
                model = "claude-haiku-4-5-20251001",
                max_tokens = 2048,
                system = Prompts.GRAMMAR_CORRECTION,
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
                response.checkStatus("Anthropic")
                
                val responseBodyString = response.body.string()
                val anthropicResponse = try {
                    NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw ApiException.ParseError()
            }
        }
    }

    override suspend fun explainCorrection(original: String, corrected: String, apiKey: String): String = withContext(Dispatchers.IO) {
        safeApiCall("Anthropic") {
            val prompt = "Original: $original\nCorrected: $corrected"

            val payload = AnthropicRequest(
                model = "claude-haiku-4-5-20251001",
                max_tokens = 300,
                system = Prompts.EXPLAIN_CORRECTION,
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
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("Anthropic")
                
                val responseBodyString = response.body.string()
                val anthropicResponse = try {
                    NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw ApiException.ParseError()
            }
        }
    }

    override suspend fun adjustTone(text: String, tone: String, apiKey: String): String = withContext(Dispatchers.IO) {
        safeApiCall("Anthropic") {
            val instruction = Prompts.getToneInstruction(tone)

            val payload = AnthropicRequest(
                model = "claude-haiku-4-5-20251001",
                max_tokens = 2048,
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
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("Anthropic")
                
                val responseBodyString = response.body.string()
                val anthropicResponse = try {
                    NetworkModule.json.decodeFromString<AnthropicResponse>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                anthropicResponse.content?.firstOrNull()?.text?.trim() ?: throw ApiException.ParseError()
            }
        }
    }
}
