package app.grammarfloat.pro.api

interface ApiClient {
    suspend fun checkGrammar(text: String, apiKey: String): String
    suspend fun explainCorrection(original: String, corrected: String, apiKey: String): String
    suspend fun adjustTone(text: String, tone: String, apiKey: String): String
}
