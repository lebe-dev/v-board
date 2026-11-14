package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import ru.tinyops.android.vboard.core.voice.EnhancementEffort
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.SelfHostedServer
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import ru.tinyops.android.vboard.diagnostics.Problems
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
            if (outcome == EnhancementOutcome.FAILED) {
                Problems.report(TAG, "The server sent an empty or unusable answer")
            }
            Result(output, outcome)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ServerHttpException) {
            val outcome = EnhancementOutcome.failure(failure.status)
            fail(text, "The server answered with HTTP status ${failure.status}", failure, outcome)
        } catch (failure: IOException) {
            fail(text, "The request to the server failed: ${failure.javaClass.simpleName}", failure)
        } catch (failure: IllegalStateException) {
            fail(text, "The request could not be built or read: ${failure.message}", failure)
        }
    }

    /** Every failure is written to the app log and reported, and the original text is kept. */
    private fun fail(
        text: String,
        message: String,
        failure: Throwable,
        outcome: EnhancementOutcome = EnhancementOutcome.FAILED,
    ): Result {
        AppLog.error(TAG, message, failure)
        ErrorReporting.capture(failure)
        return Result(text, outcome)
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

/**
 * Runs [request] for [text] and turns anything unexpected it throws into a failed result, written to the app
 * log and reported, so that a bug in a request never ends silently.
 */
internal suspend fun guardedRequest(
    text: String,
    request: suspend () -> TextEnhancer.Result,
): TextEnhancer.Result =
    runCatching { request() }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        AppLog.error("TextEnhancer", "The request failed unexpectedly: ${failure.javaClass.simpleName}", failure)
        ErrorReporting.capture(failure)
        TextEnhancer.Result(text, EnhancementOutcome.FAILED)
    }
