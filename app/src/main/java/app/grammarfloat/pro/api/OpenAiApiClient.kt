package app.grammarfloat.pro.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object OpenAiApiClient : ApiClient {

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val instructions: String,
        val input: String
    )

    override suspend fun checkGrammar(text: String, apiKey: String, modelId: String): String = withContext(Dispatchers.IO) {
        safeApiCall("OpenAI") {
            val instructions = Prompts.GRAMMAR_CORRECTION
            
            val payload = OpenAiRequest(
                model = modelId,
                instructions = instructions,
                input = text
            )

            val jsonBody = NetworkModule.json.encodeToString(payload)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("OpenAI")
                
                val responseBodyString = response.body.string()
                val jsonObject = try {
                    NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                // Robust dynamic parsing for the new v1/responses format
                val correctedText = jsonObject["output_text"]?.jsonPrimitive?.content
                    ?: jsonObject["output"]?.jsonPrimitive?.content
                    ?: jsonObject["response"]?.jsonPrimitive?.content
                    ?: jsonObject["text"]?.jsonPrimitive?.content
                    // Fallback for legacy format just in case
                    ?: jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: throw ApiException.ParseError("Could not find text in response")
                    
                correctedText.trim()
            }
        }
    }

    override suspend fun explainCorrection(original: String, corrected: String, apiKey: String, modelId: String): String = withContext(Dispatchers.IO) {
        safeApiCall("OpenAI") {
            val instructions = Prompts.EXPLAIN_CORRECTION
            val input = "Original: $original\nCorrected: $corrected"
            
            val payload = OpenAiRequest(
                model = modelId,
                instructions = instructions,
                input = input
            )

            val jsonBody = NetworkModule.json.encodeToString(payload)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("OpenAI")
                
                val responseBodyString = response.body.string()
                val jsonObject = try {
                    NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                val explanation = jsonObject["output_text"]?.jsonPrimitive?.content
                    ?: jsonObject["output"]?.jsonPrimitive?.content
                    ?: jsonObject["response"]?.jsonPrimitive?.content
                    ?: jsonObject["text"]?.jsonPrimitive?.content
                    ?: jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: throw ApiException.ParseError("Could not find text in response")
                    
                explanation.trim()
            }
        }
    }

    override suspend fun adjustTone(text: String, tone: String, apiKey: String, modelId: String): String = withContext(Dispatchers.IO) {
        safeApiCall("OpenAI") {
            val instruction = Prompts.getToneInstruction(tone)
            
            val payload = OpenAiRequest(
                model = modelId,
                instructions = instruction,
                input = text
            )

            val jsonBody = NetworkModule.json.encodeToString(payload)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBody)
                .build()

            NetworkModule.okHttpClient.newCall(request).await().use { response ->
                response.checkStatus("OpenAI")
                
                val responseBodyString = response.body.string()
                val jsonObject = try {
                    NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
                } catch (e: Exception) {
                    throw ApiException.ParseError()
                }
                
                val rewritten = jsonObject["output_text"]?.jsonPrimitive?.content
                    ?: jsonObject["output"]?.jsonPrimitive?.content
                    ?: jsonObject["response"]?.jsonPrimitive?.content
                    ?: jsonObject["text"]?.jsonPrimitive?.content
                    ?: jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                    ?: throw ApiException.ParseError("Could not find text in response")
                    
                rewritten.trim()
            }
        }
    }
}
