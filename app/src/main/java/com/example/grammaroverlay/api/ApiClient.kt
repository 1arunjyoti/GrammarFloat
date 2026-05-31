package com.example.grammaroverlay.api

interface ApiClient {
    suspend fun checkGrammar(text: String, apiKey: String): String
}
