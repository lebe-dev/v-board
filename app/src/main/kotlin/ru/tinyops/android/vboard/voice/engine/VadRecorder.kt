package ru.tinyops.android.vboard.voice.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.AssetFiles
import ru.tinyops.android.vboard.core.voice.DictationFailure
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import java.io.File

/**
 * Buffered microphone → Silero VAD → ordered recognition of every phrase on the user's server.
 * The microphone starts at once; the VAD loads while the first words are already being captured.
 *
 * [stop] ends the recording and recognizes everything captured, [cancel] drops it.
 */
internal class VadRecorder(
    private val context: Context,
) {
    @Volatile var isActive = false
        private set

    private var job: Job? = null

    @Volatile private var microphone: MicrophoneCapture? = null

    @Volatile private var stopRequested = false

    // Cancel differs from stop: it releases the microphone and discards queued and in-flight results.
    @Volatile private var discardOutput = false

    /** Starts a recording; false when the microphone cannot be opened (no permission or device). */
    fun start(
        scope: CoroutineScope,
        headset: Boolean,
        minSilenceSeconds: Float,
        transcriber: Transcriber,
        listener: DictationListener,
    ): Boolean {
        if (isActive) return false
        val input = MicrophoneCapture.open(context, scope, headset) ?: return false
        isActive = true
        stopRequested = false
        discardOutput = false
        microphone = input
        Log.i(TAG, "recording started")
        job =
            scope.launch(Dispatchers.IO) {
                try {
                    record(this, input, minSilenceSeconds, transcriber, listener)
                } finally {
                    withContext(NonCancellable) {
                        input.close()
                        microphone = null
                        isActive = false
                    }
                    Log.i(TAG, "recording stopped")
                }
            }
        return true
    }

    /** Ends capture; what was already captured goes to recognition. */
    fun stop() {
        if (!isActive || stopRequested) return
        stopRequested = true
        microphone?.stop()
    }

    /** Aborts without delivering anything. */
    fun cancel() {
        if (!isActive) return
        discardOutput = true
        stopRequested = true
        // Unblock microphone reads before cancelling recognition.
        microphone?.stop()
        job?.cancel()
    }

    private suspend fun record(
        scope: CoroutineScope,
        input: MicrophoneCapture,
        minSilenceSeconds: Float,
        transcriber: Transcriber,
        listener: DictationListener,
    ) {
        val vad =
            runCatching { vad(context, minSilenceSeconds) }
                .onFailure {
                    AppLog.error(TAG, "VAD initialization failed", it)
                    ErrorReporting.capture(it)
                }.getOrNull()
        if (vad == null) {
            withContext(NonCancellable + Dispatchers.Main) { listener.onDone(DictationFailure.VAD) }
            return
        }
        synchronized(vadLock) { runCatching { vad.reset() } }
        val segments = Channel<ByteArray>(capacity = SEGMENT_QUEUE_CAPACITY)
        val reader = scope.launch { readSegments(input, vad, segments, listener) }
        try {
            for (pcm in segments) recognize(transcriber, pcm, listener)
        } finally {
            reader.cancel()
            withContext(NonCancellable) {
                input.close()
                reader.join()
                withContext(Dispatchers.Main) { listener.onDone(null) }
            }
        }
    }

    private suspend fun recognize(
        recognizer: Transcriber,
        pcm: ByteArray,
        listener: DictationListener,
    ) {
        val started = SystemClock.elapsedRealtime()
        val text =
            try {
                recognizer.transcribe(pcm)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                // One bad segment must not end the dictation; the native layer throws anything.
                AppLog.error(TAG, "segment recognition failed", failure)
                ErrorReporting.capture(failure)
                if (!discardOutput) withContext(Dispatchers.Main) { listener.onSegmentFailed(failure) }
                return
            }
        val audioMillis = pcm.size * MILLIS_PER_SECOND / (SAMPLE_RATE * 2)
        Log.i(TAG, "~$audioMillis ms of audio in ${SystemClock.elapsedRealtime() - started} ms")
        if (!discardOutput && text.isNotBlank()) withContext(Dispatchers.Main) { listener.onSegment(text) }
    }

    private suspend fun readSegments(
        input: MicrophoneCapture,
        vad: Vad,
        segments: Channel<ByteArray>,
        listener: DictationListener,
    ) {
        val segmenter = SpeechSegmenter(vad)
        var speech = false
        val send = { cut: List<SpeechSegmenter.Segment> ->
            for (segment in cut) {
                if (!segments.trySend(segment.pcm).isSuccess) Log.w(TAG, "segment queue full, segment dropped")
            }
        }
        try {
            for (window in input.capture.frames) {
                val (cut, speechNow) = synchronized(vadLock) { segmenter.accept(window) to segmenter.speech() }
                send(cut)
                if (speechNow != speech) {
                    speech = speechNow
                    withContext(Dispatchers.Main) { listener.onSpeechActive(speechNow) }
                }
                if (window.size < VAD_WINDOW_SIZE * 2) break
            }
        } finally {
            val beforeFlush = segmenter.emitted
            if (!stopRequested) runCatching { send(synchronized(vadLock) { segmenter.flush() }) }
            // A tap ends the phrase: everything since the last segment goes, without another VAD decision.
            val minimum = if (stopRequested) 2 else MIN_TAIL_BYTES
            val tailPending = stopRequested || segmenter.emitted == beforeFlush
            if (tailPending && !discardOutput && segmenter.restBytes() >= minimum) {
                if (!segments.trySend(segmenter.rest()).isSuccess) Log.w(TAG, "tail segment could not be queued")
            }
            segments.close()
            if (speech) withContext(NonCancellable + Dispatchers.Main) { listener.onSpeechActive(false) }
        }
    }

    companion object {
        private const val TAG = "VadRecorder"
        const val SAMPLE_RATE = 16_000
        const val VAD_WINDOW_SIZE = SpeechSegmenter.WINDOW_SAMPLES
        private const val VAD_MODEL_ASSET = "voice/silero_vad.onnx"
        private const val VAD_MODEL_DIRECTORY = "models/vad"
        private const val SEGMENT_QUEUE_CAPACITY = 8
        private const val MAX_SPEECH_SEGMENT_SECONDS = 30.0f
        private const val MIN_SPEECH_SECONDS = 0.25f
        private const val VAD_THRESHOLD = 0.5f
        private const val MILLIS_PER_SECOND = 1_000

        // The emergency tail flush needs at least 0.25 s of sound.
        private const val MIN_TAIL_BYTES = SAMPLE_RATE * 2 / 4

        // One VAD reused between recordings; a different pause needs a new native instance.
        @Volatile private var sharedVad: Vad? = null
        private var sharedSilenceSeconds = 0f
        private val vadLock = Any()

        fun vad(
            context: Context,
            minSilenceSeconds: Float,
        ): Vad =
            synchronized(vadLock) {
                sharedVad?.takeIf { sharedSilenceSeconds == minSilenceSeconds } ?: run {
                    sharedVad?.release()
                    build(context, minSilenceSeconds).also {
                        sharedVad = it
                        sharedSilenceSeconds = minSilenceSeconds
                    }
                }
            }

        fun release() {
            synchronized(vadLock) {
                sharedVad?.release()
                sharedVad = null
            }
        }

        private fun build(
            context: Context,
            minSilenceSeconds: Float,
        ): Vad {
            val silero =
                SileroVadModelConfig(
                    model = vadModelPath(context),
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = minSilenceSeconds,
                    minSpeechDuration = MIN_SPEECH_SECONDS,
                    windowSize = VAD_WINDOW_SIZE,
                    maxSpeechDuration = MAX_SPEECH_SEGMENT_SECONDS,
                )
            val config =
                VadModelConfig(
                    sileroVadModelConfig = silero,
                    tenVadModelConfig = TenVadModelConfig(),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                )
            return Vad(null, config)
        }

        private fun vadModelPath(context: Context): String =
            AssetFiles
                .extract(context, VAD_MODEL_ASSET, File(context.filesDir, VAD_MODEL_DIRECTORY), "silero_vad", "onnx")
                .absolutePath
    }
}
