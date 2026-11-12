package ru.tinyops.android.vboard.core.voice

/** A well-known OpenAI-compatible service the address field can be filled with in one tap. */
data class ServerPreset(
    val name: String,
    val endpoint: String,
) {
    /** Whether a typed address is this service, ignoring case, spaces and a trailing slash. */
    fun matches(address: String): Boolean = address.trim().trimEnd('/').equals(endpoint, ignoreCase = true)
}

/** The services offered by each server form; they differ in which API they provide. */
object ServerPresets {
    private val OPENAI = ServerPreset("OpenAI", "https://api.openai.com/v1")
    private val OPENROUTER = ServerPreset("OpenRouter", "https://openrouter.ai/api/v1")
    private val GROQ = ServerPreset("Groq", "https://api.groq.com/openai/v1")
    private val DEEPSEEK = ServerPreset("DeepSeek", "https://api.deepseek.com/v1")

    /** Services with `/chat/completions`, for text improvement. */
    val CHAT = listOf(OPENAI, OPENROUTER, GROQ, DEEPSEEK)

    /** Services with `/audio/transcriptions`, for recognition on a server. */
    val TRANSCRIPTION = listOf(OPENAI, OPENROUTER, GROQ)
}
