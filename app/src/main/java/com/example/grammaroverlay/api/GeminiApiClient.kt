package com.example.grammaroverlay.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiApiClient : ApiClient {

    @Serializable
    private data class GeminiRequest(
        val systemInstruction: Content,
        val contents: List<Content>
    )

    @Serializable
    private data class Content(
        val parts: List<Part>
    )

    @Serializable
    private data class Part(
        val text: String
    )

    @Serializable
    private data class GeminiResponse(
        val candidates: List<Candidate>? = null
    )

    @Serializable
    private data class Candidate(
        val content: Content
    )

    override suspend fun checkGrammar(text: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val payload = GeminiRequest(
            systemInstruction = Content(listOf(Part("You are a professional grammar and spell checker. Respond ONLY with the corrected text. Do not add conversational padding, explanations, or quotes. If the text is already perfect, return it exactly as is."))),
            contents = listOf(Content(listOf(Part(text))))
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
            .addHeader("x-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Gemini API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body?.string() ?: throw Exception("Empty response body")
            val geminiResponse = NetworkModule.json.decodeFromString<GeminiResponse>(responseBodyString)
            
            geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim() ?: throw Exception("No content in response")
        }
    }
}
