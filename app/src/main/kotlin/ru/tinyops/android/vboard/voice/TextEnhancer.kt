package ru.tinyops.android.vboard.voice

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.core.voice.EnhancementEffort
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.voice.ServerHttp.authorize
import ru.tinyops.android.vboard.voice.ServerHttp.successBody
import java.io.IOException
import java.net.HttpURLConnection

/**
 * "Улучшение текста" through the user's own OpenAI-compatible server, as Govorun's
 * OpenAiCompatibleStylizationClient: one non-streaming chat completion with the prompt as the
 * system message and the dictated text as the user message.
 */
class TextEnhancer {
    /** The text to insert and how the request went; any failure falls back to the original text. */
    data class Result(
        val text: String,
        val outcome: EnhancementOutcome,
    )

    suspend fun enhance(
        server: SelfHostedServer,
        text: String,
        effort: EnhancementEffort = EnhancementEffort.DEFAULT,
    ): Result {
        if (text.isBlank()) return Result(text, EnhancementOutcome.UNCHANGED)
        return try {
            val (output, outcome) = EnhancementOutcome.resolve(text, complete(server, text, effort))
            Result(output, outcome)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ServerHttpException) {
            Log.w(TAG, "server answered HTTP ${failure.status}")
            Result(text, EnhancementOutcome.failure(failure.status))
        } catch (failure: IOException) {
            Log.w(TAG, "request failed: ${failure.javaClass.simpleName}")
            Result(text, EnhancementOutcome.FAILED)
        } catch (failure: IllegalStateException) {
            Log.w(TAG, "unusable response: ${failure.message}")
            Result(text, EnhancementOutcome.FAILED)
        }
    }

    /** The raw completion; the connection is dropped when the coroutine is cancelled. */
    private suspend fun complete(
        server: SelfHostedServer,
        text: String,
        effort: EnhancementEffort,
    ): String {
        val url = checkNotNull(server.chatUrl) { "invalid server address" }
        return ServerHttp.call(url, server.proxy) { connection -> exchange(connection, server, text, effort) }
    }

    private fun exchange(
        connection: HttpURLConnection,
        server: SelfHostedServer,
        text: String,
        effort: EnhancementEffort,
    ): String {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.authorize(server.apiKey)
        val body = buildRequest(server.model, server.prompt, text, effort)
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return parseResponse(connection.successBody())
    }

    companion object {
        private const val TAG = "TextEnhancer"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 180_000

        fun buildRequest(
            model: String,
            instruction: String,
            text: String,
            effort: EnhancementEffort = EnhancementEffort.DEFAULT,
        ): JSONObject =
            JSONObject()
                .put("model", model.trim())
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", instruction.trim()))
                        .put(JSONObject().put("role", "user").put("content", text)),
                ).put("reasoning_effort", effort.id)
                .put("stream", false)

        /** `choices[0].message.content`; anything else is an unusable response. */
        fun parseResponse(raw: String): String {
            val content =
                runCatching {
                    JSONObject(raw)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.opt("content")
                }.getOrNull()
            return (content as? String)?.trim().orEmpty()
        }
    }
}
