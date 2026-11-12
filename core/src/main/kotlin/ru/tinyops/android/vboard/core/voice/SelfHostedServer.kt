package ru.tinyops.android.vboard.core.voice

import java.net.URI

/**
 * "Улучшение текста": the user's own OpenAI-compatible server (Govorun's "Свой сервер"). The
 * recognized text and the prompt go straight to that server; audio never leaves the device.
 */
data class SelfHostedServer(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val prompt: String = DEFAULT_PROMPT,
    val proxyEnabled: Boolean = false,
    val proxyUrl: String = "",
    /** The [recipient] the user agreed to send text to. */
    val acceptedRecipient: String = "",
) {
    /** The chat completions URL, or null when [endpoint] is not a usable http(s) address. */
    val chatUrl: String? get() = ServerAddress.chat(endpoint)

    /** `scheme://host:port` of the proxy, or null when it is off or invalid. */
    val proxy: String? get() = if (proxyEnabled) ServerAddress.proxy(proxyUrl) else null

    /** Who receives the text: the server and the proxy in between. Consent is bound to it. */
    val recipient: String?
        get() {
            val chat = chatUrl ?: return null
            return if (proxyEnabled) proxy?.let { "$chat\n$it" } else chat
        }

    val hasConsent: Boolean get() = acceptedRecipient.isNotEmpty() && acceptedRecipient == recipient

    /** Everything needed for a request is filled in. */
    val isComplete: Boolean
        get() =
            chatUrl != null &&
                model.isNotBlank() &&
                prompt.trim().length >= MIN_PROMPT_LENGTH &&
                (!proxyEnabled || proxy != null)

    /** Plain HTTP leaves the text and the key unencrypted on the way. */
    val insecure: Boolean get() = endpoint.trim().startsWith("http://", ignoreCase = true)

    /** Identifies the checked configuration: a change of server, model or key needs a new check. */
    val fingerprint: String? get() = chatUrl?.let { "$it\n${model.trim()}\n${apiKey.trim().hashCode()}" }

    /** Values as they are stored: trimmed and bounded. */
    fun normalized(): SelfHostedServer =
        copy(
            endpoint = endpoint.trim(),
            model = model.trim().take(MAX_MODEL_LENGTH),
            apiKey = apiKey.trim().take(MAX_API_KEY_LENGTH),
            prompt = prompt.trim(),
            proxyUrl = proxyUrl.trim(),
        )

    companion object {
        const val MAX_MODEL_LENGTH = 120
        const val MAX_API_KEY_LENGTH = 4_096
        const val MIN_PROMPT_LENGTH = 3
        const val DEFAULT_PROMPT =
            "Исправь ошибки и опечатки, убери повторы и расставь знаки. " +
                "Не меняй смысл. Верни только готовый текст."
    }
}

/** Address rules of Govorun's self-hosted server settings. */
object ServerAddress {
    private const val CHAT_COMPLETIONS = "/chat/completions"
    private const val AUDIO_TRANSCRIPTIONS = "/audio/transcriptions"
    private const val HTTP_PROXY_PORT = 8080
    private const val SOCKS_PROXY_PORT = 1080
    private const val MAX_PORT = 65_535

    /** `https://host/v1` → `https://host/v1/chat/completions`; an address may already end with it. */
    fun chat(value: String): String? = api(value, CHAT_COMPLETIONS)

    /** `https://host/v1` → `https://host/v1/audio/transcriptions`; an address may already end with it. */
    fun transcriptions(value: String): String? = api(value, AUDIO_TRANSCRIPTIONS)

    private fun api(
        value: String,
        method: String,
    ): String? {
        val uri = parse(value.trim().trimEnd('/')) ?: return null
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.fragment != null) return null
        val path = apiPath(uri.path.orEmpty(), method)
        return runCatching { URI(scheme, null, uri.host, uri.port, path, uri.query, null) }
            .getOrNull()
            ?.toASCIIString()
    }

    /** HTTP or SOCKS5 proxy without credentials; the port defaults to 8080 or 1080. */
    fun proxy(value: String): String? {
        val uri = parse(value.trim()) ?: return null
        val scheme = uri.scheme?.lowercase()
        val valid =
            (scheme == "http" || scheme == "socks5") &&
                uri.query == null &&
                uri.fragment == null &&
                uri.path.orEmpty().let { it.isEmpty() || it == "/" } &&
                (uri.port == -1 || uri.port in 1..MAX_PORT)
        if (!valid) return null
        val port =
            if (uri.port != -1) {
                uri.port
            } else if (scheme == "socks5") {
                SOCKS_PROXY_PORT
            } else {
                HTTP_PROXY_PORT
            }
        return "$scheme://${uri.host.lowercase()}:$port"
    }

    private fun parse(value: String): URI? =
        runCatching { URI(value).normalize() }
            .getOrNull()
            ?.takeIf { !it.host.isNullOrEmpty() && it.userInfo == null && !it.rawAuthority.orEmpty().contains('@') }

    /** Appends [method] (`/group/name`) to an API base; the base may already end with it or its group. */
    private fun apiPath(
        path: String,
        method: String,
    ): String {
        val trimmed = path.trimEnd('/')
        val group = method.substringBeforeLast('/')
        return when {
            trimmed.endsWith(method) -> trimmed
            trimmed.endsWith(group) -> trimmed + method.removePrefix(group)
            else -> "$trimmed$method"
        }
    }
}

/** How an improvement request ended; the original text is inserted whenever it did not work. */
enum class EnhancementOutcome {
    SUCCESS,
    UNCHANGED,
    FAILED,
    AUTH_FAILED,
    ;

    companion object {
        /** The text to insert for a server [output] and how the request went. */
        fun resolve(
            input: String,
            output: String,
        ): Pair<String, EnhancementOutcome> =
            when {
                output.isBlank() -> input to FAILED
                output.trim() == input.trim() -> output.trim() to UNCHANGED
                else -> output.trim() to SUCCESS
            }

        fun failure(httpStatus: Int): EnhancementOutcome =
            if (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN) AUTH_FAILED else FAILED

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }
}
