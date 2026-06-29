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

object GeminiApiClient : ApiClient {

    // ── Legacy generateContent API (gemini-1.x / gemini-2.x) ─────────────────

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

    // ── New Interactions API (gemini-3.x+) ───────────────────────────────────

    @Serializable
    private data class InteractionsRequest(
        val model: String,
        val system_instruction: String,
        val input: String
    )

    // ── Routing helper ───────────────────────────────────────────────────────

    /**
     * Returns true for models that use the newer /v1beta/interactions endpoint
     * (e.g. gemini-3.5-flash). Older gemini-1.x / gemini-2.x models continue
     * to use the /v1beta/models/{model}:generateContent endpoint.
     *
     * NOTE: This assumes all gemini-3.x models use the interactions API. If Google
     * releases a gemini-3.x model that uses generateContent, this routing will break
     * and should be made user-configurable or explicitly mapped.
     */
    private fun usesInteractionsApi(modelId: String): Boolean {
        val normalized = modelId.substringAfterLast("/") // strip "models/" prefix if present
        val major = normalized.removePrefix("gemini-")
            .split("-", ".").firstOrNull()?.toIntOrNull() ?: return false
        return major >= 3
    }

    private fun extractInteractionsText(json: JsonObject): String? {
        // Interactions API response shape:
        // { "steps": [ { "type": "thought", ... }, { "type": "model_output", "content": [{ "type": "text", "text": "..." }] } ] }
        json["steps"]?.jsonArray?.forEach { step ->
            val stepObj = step.jsonObject
            if (stepObj["type"]?.jsonPrimitive?.content == "model_output") {
                stepObj["content"]?.jsonArray?.forEach { contentItem ->
                    val contentObj = contentItem.jsonObject
                    if (contentObj["type"]?.jsonPrimitive?.content == "text") {
                        contentObj["text"]?.jsonPrimitive?.content?.let { return it }
                    }
                }
            }
        }
        return null
    }

    // ── ApiClient implementation ─────────────────────────────────────────────

    override suspend fun checkGrammar(text: String, apiKey: String, modelId: String): String =
        withContext(Dispatchers.IO) {
            safeApiCall("Gemini") {
                if (usesInteractionsApi(modelId)) {
                    callInteractionsApi(
                        apiKey = apiKey,
                        modelId = modelId,
                        systemInstruction = Prompts.GRAMMAR_CORRECTION,
                        input = text
                    )
                } else {
                    callGenerateContentApi(
                        apiKey = apiKey,
                        modelId = modelId,
                        systemInstruction = Prompts.GRAMMAR_CORRECTION,
                        userText = text
                    )
                }
            }
        }

    override suspend fun explainCorrection(
        original: String,
        corrected: String,
        apiKey: String,
        modelId: String
    ): String = withContext(Dispatchers.IO) {
        safeApiCall("Gemini") {
            val prompt = "Original: $original\nCorrected: $corrected"
            if (usesInteractionsApi(modelId)) {
                callInteractionsApi(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = Prompts.EXPLAIN_CORRECTION,
                    input = prompt
                )
            } else {
                callGenerateContentApi(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = Prompts.EXPLAIN_CORRECTION,
                    userText = prompt
                )
            }
        }
    }

    override suspend fun adjustTone(
        text: String,
        tone: String,
        apiKey: String,
        modelId: String
    ): String = withContext(Dispatchers.IO) {
        safeApiCall("Gemini") {
            val instruction = Prompts.getToneInstruction(tone)
            if (usesInteractionsApi(modelId)) {
                callInteractionsApi(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = instruction,
                    input = text
                )
            } else {
                callGenerateContentApi(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = instruction,
                    userText = text
                )
            }
        }
    }

    // ── Private call helpers ─────────────────────────────────────────────────

    /** POST /v1beta/interactions — used by gemini-3.x+ models */
    private suspend fun callInteractionsApi(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        input: String
    ): String {
        val modelResourceName = if (modelId.startsWith("models/")) modelId else "models/$modelId"
        val payload = InteractionsRequest(
            model = modelResourceName,
            system_instruction = systemInstruction,
            input = input
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .addHeader("x-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        return NetworkModule.okHttpClient.newCall(request).await().use { response ->
            response.checkStatus("Gemini")

            val responseBodyString = response.body.string()

            val jsonObject = try {
                NetworkModule.json.decodeFromString<JsonObject>(responseBodyString)
            } catch (e: Exception) {
                throw ApiException.ParseError("Non-JSON response: ${responseBodyString.take(200)}")
            }

            extractInteractionsText(jsonObject)?.trim()
                ?: throw ApiException.ParseError("Could not find text in Gemini interactions response")
        }
    }

    /** POST /v1beta/models/{model}:generateContent — used by gemini-1.x / gemini-2.x */
    private suspend fun callGenerateContentApi(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userText: String
    ): String {
        val payload = GeminiRequest(
            systemInstruction = Content(listOf(Part(systemInstruction))),
            contents = listOf(Content(listOf(Part(userText))))
        )

        val jsonBody = NetworkModule.json.encodeToString(payload)
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val normalizedModelId = modelId.substringAfterLast("/")
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$normalizedModelId:generateContent")
            .addHeader("x-goog-api-key", apiKey.trim())
            .post(requestBody)
            .build()

        return NetworkModule.okHttpClient.newCall(request).await().use { response ->
            response.checkStatus("Gemini")

            val responseBodyString = response.body.string()
            val geminiResponse = try {
                NetworkModule.json.decodeFromString<GeminiResponse>(responseBodyString)
            } catch (e: Exception) {
                throw ApiException.ParseError()
            }

            geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                ?: throw ApiException.ParseError()
        }
    }
}
