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
        val input: String
    )

    override suspend fun checkGrammar(text: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val prompt = "You are a professional grammar and spell checker. Respond ONLY with the corrected text. Do not add conversational padding, explanations, or quotes. If the text is already perfect, return it exactly as is.\n\nText: $text"
        
        val payload = OpenAiRequest(
            model = "gpt-5.4-nano",
            input = prompt
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OpenAI API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body.string()
            val jsonObject = NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
            
            // Robust dynamic parsing for the new v1/responses format
            val correctedText = jsonObject["output_text"]?.jsonPrimitive?.content
                ?: jsonObject["output"]?.jsonPrimitive?.content
                ?: jsonObject["response"]?.jsonPrimitive?.content
                ?: jsonObject["text"]?.jsonPrimitive?.content
                // Fallback for legacy format just in case
                ?: jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw Exception("Could not find text in response")
                
            correctedText.trim()
        }
    }

    override suspend fun explainCorrection(original: String, corrected: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val prompt = "Briefly explain in 1-2 sentences why the following text was corrected.\n\nOriginal: $original\nCorrected: $corrected"
        
        val payload = OpenAiRequest(
            model = "gpt-5.4-nano",
            input = prompt
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OpenAI API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body.string()
            val jsonObject = NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
            
            val explanation = jsonObject["output_text"]?.jsonPrimitive?.content
                ?: jsonObject["output"]?.jsonPrimitive?.content
                ?: jsonObject["response"]?.jsonPrimitive?.content
                ?: jsonObject["text"]?.jsonPrimitive?.content
                ?: jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw Exception("Could not find text in response")
                
            explanation.trim()
        }
    }

    override suspend fun adjustTone(text: String, tone: String, apiKey: String): String = withContext(Dispatchers.IO) {
        val instruction = when (tone.lowercase()) {
            "professional" -> "Rewrite the following text to sound highly professional, formal, and polished. Respond ONLY with the rewritten text."
            "casual" -> "Rewrite the following text to sound casual, friendly, and conversational. Respond ONLY with the rewritten text."
            "shorten" -> "Rewrite the following text to be as concise and short as possible without losing the main meaning. Respond ONLY with the rewritten text."
            else -> "Rewrite the following text. Respond ONLY with the rewritten text."
        }
        
        val prompt = "$instruction\n\nText: $text"
        
        val payload = OpenAiRequest(
            model = "gpt-5.4-nano",
            input = prompt
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(requestBody)
            .build()

        NetworkModule.okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OpenAI API error: ${response.code} ${response.message}")
            }
            
            val responseBodyString = response.body.string()
            val jsonObject = NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
            
            val rewritten = jsonObject["output_text"]?.jsonPrimitive?.content
                ?: jsonObject["output"]?.jsonPrimitive?.content
                ?: jsonObject["response"]?.jsonPrimitive?.content
                ?: jsonObject["text"]?.jsonPrimitive?.content
                ?: jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw Exception("Could not find text in response")
                
            rewritten.trim()
        }
    }
}
