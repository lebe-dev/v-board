package ru.tinyops.android.vboard.core.voice

import java.net.URI

/** What a server is used for: improving text or recognizing audio. */
enum class ServerKind(
    val id: String,
) {
    TEXT("text"),
    AUDIO("audio"),
    ;

    companion object {
        /** The kind stored under [id]; servers saved before kinds existed are text servers. */
        fun fromId(id: String?): ServerKind = entries.firstOrNull { it.id == id } ?: TEXT
    }
}

/**
 * One ready-made server of the list: an OpenAI-compatible API with the model, the key and the level of
 * effort to use on it. Text improvement and recognition on a server both choose from this list, so a
 * server is edited in one place only. Choosing a server for a function is the consent to send data to it.
 */
data class ServerProfile(
    val id: String,
    val name: String = "",
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val effort: EnhancementEffort = EnhancementEffort.DEFAULT,
    val proxyEnabled: Boolean = false,
    val proxyUrl: String = "",
    val kind: ServerKind = ServerKind.TEXT,
) {
    /** The name to show: the one the user gave, or the host and the model when there is none. */
    val displayName: String
        get() {
            if (name.isNotBlank()) return name
            val host = runCatching { URI(endpoint.trim()).host }.getOrNull().orEmpty()
            return listOf(host, model.trim()).filter(String::isNotEmpty).joinToString(" · ")
        }

    /** `scheme://host:port` of the proxy, or null when it is off or invalid. */
    val proxy: String? get() = if (proxyEnabled) ServerAddress.proxy(proxyUrl) else null

    /** Plain HTTP leaves the data and the key unencrypted on the way. */
    val insecure: Boolean get() = endpoint.trim().startsWith("http://", ignoreCase = true)

    /** The address is usable, a model is set and the proxy, if on, is valid. */
    val isComplete: Boolean
        get() = ServerAddress.chat(endpoint) != null && model.isNotBlank() && (!proxyEnabled || proxy != null)

    /** The server as text improvement uses it, sending [prompt] with the text. */
    fun forEnhancement(prompt: String): SelfHostedServer {
        val server = SelfHostedServer(endpoint, model, apiKey, prompt, proxyEnabled, proxyUrl)
        return server.copy(acceptedRecipient = server.recipient.orEmpty())
    }

    /** The server as recognition uses it. */
    fun forRecognition(
        language: String,
        promptEnabled: Boolean,
        prompt: String,
    ): RemoteRecognitionServer {
        val server =
            RemoteRecognitionServer(
                endpoint = endpoint,
                model = model,
                apiKey = apiKey,
                language = language,
                proxyEnabled = proxyEnabled,
                proxyUrl = proxyUrl,
                promptEnabled = promptEnabled,
                prompt = prompt,
            )
        return server.copy(acceptedRecipient = server.recipient.orEmpty())
    }

    /** Values as they are stored: trimmed and bounded. */
    fun normalized(): ServerProfile =
        copy(
            name = name.trim().take(MAX_NAME_LENGTH),
            endpoint = endpoint.trim(),
            model = model.trim().take(SelfHostedServer.MAX_MODEL_LENGTH),
            apiKey = apiKey.trim().take(SelfHostedServer.MAX_API_KEY_LENGTH),
            proxyUrl = proxyUrl.trim(),
        )

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}

/** The server with the given [id], or null when there is none or nothing is chosen. */
fun List<ServerProfile>.find(id: String?): ServerProfile? = if (id == null) null else firstOrNull { it.id == id }

/** Adds the server, or replaces the one with the same id keeping its position. */
fun List<ServerProfile>.withProfile(profile: ServerProfile): List<ServerProfile> {
    val stored = profile.normalized()
    return if (find(stored.id) == null) this + stored else map { if (it.id == stored.id) stored else it }
}

fun List<ServerProfile>.withoutProfile(id: String): List<ServerProfile> = filterNot { it.id == id }
