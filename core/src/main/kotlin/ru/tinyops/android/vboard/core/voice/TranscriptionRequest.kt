package ru.tinyops.android.vboard.core.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM as a WAV file, the format every transcription API accepts. */
object Wav {
    private const val HEADER_BYTES = 44
    private const val RIFF_HEADER_REST = 36
    private const val FMT_CHUNK_BYTES = 16
    private const val PCM_FORMAT: Short = 1
    private const val CHANNELS: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16
    private const val BYTES_PER_SAMPLE = 2

    fun encode(
        pcm: ByteArray,
        sampleRate: Int,
    ): ByteArray =
        ByteBuffer
            .allocate(HEADER_BYTES + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII))
            .putInt(RIFF_HEADER_REST + pcm.size)
            .put("WAVE".toByteArray(Charsets.US_ASCII))
            .put("fmt ".toByteArray(Charsets.US_ASCII))
            .putInt(FMT_CHUNK_BYTES)
            .putShort(PCM_FORMAT)
            .putShort(CHANNELS)
            .putInt(sampleRate)
            .putInt(sampleRate * BYTES_PER_SAMPLE)
            .putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
            .putShort(BITS_PER_SAMPLE)
            .put("data".toByteArray(Charsets.US_ASCII))
            .putInt(pcm.size)
            .put(pcm)
            .array()

    /** [millis] of silence, the audio of the connection check. */
    fun silence(
        millis: Int,
        sampleRate: Int,
    ): ByteArray = encode(ByteArray(sampleRate * BYTES_PER_SAMPLE * millis / MILLIS_PER_SECOND), sampleRate)

    private const val MILLIS_PER_SECOND = 1_000
}

/**
 * The `multipart/form-data` body of `POST /audio/transcriptions` (OpenAI's format, also accepted by
 * OpenRouter): `file`, `model`, optional `language` and `prompt`, `response_format=json`, answered with
 * `{"text": "…"}`.
 */
class TranscriptionRequest(
    private val model: String,
    private val language: String,
    private val wav: ByteArray,
    private val prompt: String = "",
    val boundary: String = "----keyboard-${System.nanoTime().toString(RADIX)}",
) {
    val contentType: String get() = "multipart/form-data; boundary=$boundary"

    fun body(): ByteArray {
        val out = ByteArrayOutputStream(wav.size + OVERHEAD_BYTES)

        fun line(text: String = "") = out.write("$text\r\n".toByteArray(Charsets.UTF_8))

        fun field(
            name: String,
            value: String,
        ) {
            line("--$boundary")
            line("Content-Disposition: form-data; name=\"$name\"")
            line()
            line(value)
        }

        line("--$boundary")
        line("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"")
        line("Content-Type: audio/wav")
        line()
        out.write(wav)
        line()
        field("model", model.trim())
        language.trim().takeIf(String::isNotEmpty)?.let { field("language", it.lowercase()) }
        prompt.trim().takeIf(String::isNotEmpty)?.let { field("prompt", it) }
        field("response_format", "json")
        line("--$boundary--")
        return out.toByteArray()
    }

    private companion object {
        const val RADIX = 36
        const val OVERHEAD_BYTES = 1_024
    }
}
