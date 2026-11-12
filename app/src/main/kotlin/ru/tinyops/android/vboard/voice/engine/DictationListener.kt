package ru.tinyops.android.vboard.voice.engine

import ru.tinyops.android.vboard.core.voice.DictationFailure

/** Turns one speech segment (16 kHz, 16-bit mono PCM) into text. */
internal fun interface Transcriber {
    suspend fun transcribe(pcm: ByteArray): String
}

/** What a recording reports, on the main thread. */
internal interface DictationListener {
    /** The recognizer is loaded; the microphone has been capturing since the tap. */
    fun onReady() = Unit

    suspend fun onSegment(text: String)

    /** A phrase could not be recognized and is lost; the recording goes on. */
    suspend fun onSegmentFailed(failure: Exception) = Unit

    /** Speech started or stopped. */
    fun onSpeechActive(active: Boolean)

    /** Always called last, also for empty or failed recordings. */
    suspend fun onDone(failure: DictationFailure?)
}
