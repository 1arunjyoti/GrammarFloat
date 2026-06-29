package app.grammarfloat.pro.api

object ModelDefaults {
    fun forProvider(provider: Provider): String = when (provider) {
        Provider.ANTHROPIC -> "claude-haiku-4-5-20251001"
        Provider.OPENAI    -> "gpt-5.4-nano"
        Provider.GEMINI    -> "gemini-3.1-flash-lite"
    }
}
