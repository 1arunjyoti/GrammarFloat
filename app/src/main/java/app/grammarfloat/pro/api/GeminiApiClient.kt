package app.grammarfloat.pro.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object GeminiApiClient : ApiClient {

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
        safeApiCall("Gemini") {
            val payload = GeminiRequest(
                systemInstruction = Content(listOf(Part(Prompts.GRAMMAR_CORRECTION))),
                contents = listOf(Content(listOf(Part(text))))
            )

            val jsonBody = NetworkModule.json.encodeToString(payload)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
                .addHeader("x-goog-api-key", apiKey.trim())
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("Gemini")
                
                val responseBodyString = response.body.string()
                val geminiResponse = try {
                    NetworkModule.json.decodeFromString<GeminiResponse>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim() ?: throw ApiException.ParseError()
            }
        }
    }

    override suspend fun explainCorrection(original: String, corrected: String, apiKey: String): String = withContext(Dispatchers.IO) {
        safeApiCall("Gemini") {
            val prompt = "Original: $original\nCorrected: $corrected"

            val payload = GeminiRequest(
                systemInstruction = Content(
                    parts = listOf(Part(text = Prompts.EXPLAIN_CORRECTION))
                ),
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                )
            )

            val jsonBody = NetworkModule.json.encodeToString(payload)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
                .addHeader("x-goog-api-key", apiKey.trim())
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("Gemini")
                
                val responseBodyString = response.body.string()
                val geminiResponse = try {
                    NetworkModule.json.decodeFromString<GeminiResponse>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim() ?: throw ApiException.ParseError()
            }
        }
    }

    override suspend fun adjustTone(text: String, tone: String, apiKey: String): String = withContext(Dispatchers.IO) {
        safeApiCall("Gemini") {
            val instruction = Prompts.getToneInstruction(tone)

            val payload = GeminiRequest(
                systemInstruction = Content(
                    parts = listOf(Part(text = instruction))
                ),
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = text))
                    )
                )
            )

            val jsonBody = NetworkModule.json.encodeToString(payload)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
                .addHeader("x-goog-api-key", apiKey.trim())
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("Gemini")
                
                val responseBodyString = response.body.string()
                val geminiResponse = try {
                    NetworkModule.json.decodeFromString<GeminiResponse>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim() ?: throw ApiException.ParseError()
            }
        }
    }
}
