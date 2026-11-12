package ru.tinyops.android.vboard.voice

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import ru.tinyops.android.vboard.core.voice.RemoteRecognitionServer
import ru.tinyops.android.vboard.core.voice.ServerCheck
import ru.tinyops.android.vboard.core.voice.TranscriptionRequest
import ru.tinyops.android.vboard.core.voice.Wav
import ru.tinyops.android.vboard.voice.ServerHttp.authorize
import ru.tinyops.android.vboard.voice.ServerHttp.successBody
import ru.tinyops.android.vboard.voice.engine.Transcriber
import ru.tinyops.android.vboard.voice.engine.VadRecorder
import java.io.IOException
import java.net.HttpURLConnection

/**
 * "Распознавание на сервере": every VAD phrase goes as a WAV file to the user's OpenAI-compatible
 * `/audio/transcriptions` API (OpenAI, OpenRouter, Groq, speaches…). Failures throw: the recorder
 * reports them and goes on with the next phrase.
 */
internal class RemoteTranscriber(
    private val server: RemoteRecognitionServer,
) : Transcriber {
    override suspend fun transcribe(pcm: ByteArray): String {
        if (pcm.isEmpty()) return ""
        val text = server.withoutPromptEcho(request(Wav.encode(pcm, VadRecorder.SAMPLE_RATE)))
        // Privacy: the length only, never the dictated text, goes to logcat.
        Log.i(TAG, "server transcribed ${pcm.size} B into ${text.length} chars")
        return text
    }

    /** "Подключить": one second of silence; any 2xx answer with a `text` field proves the setup works. */
    suspend fun check(): ServerCheck =
        try {
            request(Wav.silence(CHECK_MILLIS, VadRecorder.SAMPLE_RATE))
            ServerCheck.OK
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ServerHttpException) {
            Log.w(TAG, "check: server answered HTTP ${failure.status}")
            ServerCheck.failure(failure.status)
        } catch (failure: IOException) {
            Log.w(TAG, "check failed: ${failure.javaClass.simpleName}")
            ServerCheck.FAILED
        } catch (failure: IllegalStateException) {
            Log.w(TAG, "check: unusable response: ${failure.message}")
            ServerCheck.FAILED
        }

    private suspend fun request(wav: ByteArray): String {
        val url = checkNotNull(server.transcriptionUrl) { "invalid server address" }
        val request = TranscriptionRequest(server.model, server.language, wav, server.requestPrompt)
        return ServerHttp.call(url, server.proxy) { connection -> exchange(connection, request) }
    }

    private fun exchange(
        connection: HttpURLConnection,
        request: TranscriptionRequest,
    ): String {
        val body = request.body()
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.setRequestProperty("Content-Type", request.contentType)
        connection.setRequestProperty("Accept", "application/json")
        connection.authorize(server.apiKey)
        connection.outputStream.use { it.write(body) }
        return parseResponse(connection.successBody())
    }

    companion object {
        private const val TAG = "RemoteTranscriber"
        private const val CONNECT_TIMEOUT_MS = 15_000

        // A phrase is at most 30 s of audio (the VAD cuts longer speech).
        private const val READ_TIMEOUT_MS = 60_000
        private const val CHECK_MILLIS = 1_000

        /** `{"text": "…"}`; a missing field is an unusable response. */
        fun parseResponse(raw: String): String {
            val text = runCatching { JSONObject(raw).opt("text") }.getOrNull()
            check(text is String) { "no text in the response" }
            return text.trim()
        }
    }
}
