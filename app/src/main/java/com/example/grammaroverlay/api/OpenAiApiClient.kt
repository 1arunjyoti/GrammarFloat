package com.example.grammaroverlay.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiApiClient : ApiClient {

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val messages: List<Message>,
        val max_tokens: Int
    )

    @Serializable
    private data class Message(
        val role: String,
        val content: String
    )

    @Serializable
    private data class OpenAiResponse(
        val choices: List<Choice>? = null
    )

    @Serializable
    private data class Choice(
        val message: Message
    )

    override suspend fun checkGrammar(text: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val payload = OpenAiRequest(
            model = "gpt-3.5-turbo",
            max_tokens = 1024,
            messages = listOf(
                Message("system", "You are a professional grammar and spell checker. Respond ONLY with the corrected text. Do not add conversational padding, explanations, or quotes. If the text is already perfect, return it exactly as is."),
                Message("user", text)
            )
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OpenAI API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body?.string() ?: throw Exception("Empty response body")
            val openAiResponse = NetworkModule.json.decodeFromString<OpenAiResponse>(responseBodyString)
            
            openAiResponse.choices?.firstOrNull()?.message?.content?.trim() ?: throw Exception("No content in response")
        }
    }
}
