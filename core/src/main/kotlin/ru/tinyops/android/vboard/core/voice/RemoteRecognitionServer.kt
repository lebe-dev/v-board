package ru.tinyops.android.vboard.core.voice

/**
 * "Распознавание на сервере": speech goes to an OpenAI-compatible `/audio/transcriptions` API
 * (OpenAI, OpenRouter, Groq, speaches…) instead of the engine chosen on the device. Every phrase
 * cut by the VAD is one request; the VAD is the only thing that runs on the device.
 */
data class RemoteRecognitionServer(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    /** ISO-639-1 hint for the model; empty lets the server detect the language. */
    val language: String = DEFAULT_LANGUAGE,
    val proxyEnabled: Boolean = false,
    val proxyUrl: String = "",
    /** The [recipient] the user agreed to send audio to. */
    val acceptedRecipient: String = "",
    /** Whether [prompt] goes with every phrase; off sends the voice alone. */
    val promptEnabled: Boolean = false,
    /** The `prompt` of the transcription API: a hint with the style and the terms to expect. */
    val prompt: String = DEFAULT_PROMPT,
) {
    /** The transcriptions URL, or null when [endpoint] is not a usable http(s) address. */
    val transcriptionUrl: String? get() = ServerAddress.transcriptions(endpoint)

    /** `scheme://host:port` of the proxy, or null when it is off or invalid. */
    val proxy: String? get() = if (proxyEnabled) ServerAddress.proxy(proxyUrl) else null

    /** Who receives the audio: the server and the proxy in between. Consent is bound to it. */
    val recipient: String?
        get() {
            val url = transcriptionUrl ?: return null
            return if (proxyEnabled) proxy?.let { "$url\n$it" } else url
        }

    val hasConsent: Boolean get() = acceptedRecipient.isNotEmpty() && acceptedRecipient == recipient

    val languageValid: Boolean get() = language.trim().let { it.isEmpty() || LANGUAGE.matches(it) }

    /** What goes in the request's `prompt` field; empty leaves the field out. */
    val requestPrompt: String get() = if (promptEnabled) prompt.trim() else ""

    val promptValid: Boolean get() = !promptEnabled || requestPrompt.length >= SelfHostedServer.MIN_PROMPT_LENGTH

    /** Everything needed for a request is filled in. */
    val isComplete: Boolean
        get() =
            transcriptionUrl != null &&
                model.isNotBlank() &&
                languageValid &&
                promptValid &&
                (!proxyEnabled || proxy != null)

    /** Plain HTTP leaves the audio and the key unencrypted on the way. */
    val insecure: Boolean get() = endpoint.trim().startsWith("http://", ignoreCase = true)

    /** Identifies the checked configuration: a change of server, model or key needs a new check. */
    val fingerprint: String?
        get() = transcriptionUrl?.let { "$it\n${model.trim()}\n${apiKey.trim().hashCode()}" }

    /** Whisper models answer noise with their prompt: that text was never said, so it is not inserted. */
    fun withoutPromptEcho(text: String): String =
        if (requestPrompt.isNotEmpty() && text.trim().equals(requestPrompt, ignoreCase = true)) "" else text

    /** Values as they are stored: trimmed and bounded. */
    fun normalized(): RemoteRecognitionServer =
        copy(
            endpoint = endpoint.trim(),
            model = model.trim().take(SelfHostedServer.MAX_MODEL_LENGTH),
            apiKey = apiKey.trim().take(SelfHostedServer.MAX_API_KEY_LENGTH),
            language = language.trim().lowercase(),
            proxyUrl = proxyUrl.trim(),
            prompt = prompt.trim().take(MAX_PROMPT_LENGTH),
        )

    companion object {
        const val DEFAULT_LANGUAGE = "ru"
        const val MAX_PROMPT_LENGTH = 1_000

        /** Worded for both kinds of models: an instruction for gpt-4o-*, a punctuation sample for Whisper. */
        const val DEFAULT_PROMPT = "Расшифруй речь дословно, расставь запятые, точки и заглавные буквы."
        private val LANGUAGE = Regex("[a-zA-Z]{2,3}")
    }
}

/** How a connection check ended. */
enum class ServerCheck {
    OK,
    AUTH_FAILED,
    FAILED,
    ;

    companion object {
        fun failure(httpStatus: Int): ServerCheck =
            if (EnhancementOutcome.failure(httpStatus) == EnhancementOutcome.AUTH_FAILED) AUTH_FAILED else FAILED
    }
}
