package ru.tinyops.android.vboard.voice.govorun

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.govorun.api.v1.IContinuousRecognitionCallback
import com.govorun.api.v1.IRecognitionService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.tinyops.android.vboard.core.voice.DictationFailure
import ru.tinyops.android.vboard.core.voice.GovorunApi
import ru.tinyops.android.vboard.voice.engine.DictationListener
import ru.tinyops.android.vboard.voice.engine.MicrophoneCapture
import java.io.IOException
import java.util.UUID

/**
 * Dictation through the installed Govorun app: the keyboard owns the microphone and streams PCM
 * into a pipe, Govorun finds the phrases with its VAD and answers with text, phrase by phrase.
 *
 * [stop] closes the stream and everything said so far is still recognized, [cancel] drops it.
 * Main thread only.
 */
internal class GovorunRecorder(
    context: Context,
) {
    private val context = context.applicationContext
    private val connection = GovorunConnection(context)
    private var job: Job? = null
    private var microphone: MicrophoneCapture? = null

    @Volatile private var discardOutput = false

    /** Starts capturing at once; false when the microphone cannot be opened. */
    fun start(
        scope: CoroutineScope,
        headset: Boolean,
        profile: Int,
        listener: DictationListener,
    ): Boolean {
        if (job != null) return false
        val input = MicrophoneCapture.open(context, scope, headset) ?: return false
        microphone = input
        discardOutput = false
        job =
            scope.launch {
                var failure: DictationFailure? = DictationFailure.RECOGNITION
                try {
                    failure = record(scope, input, profile, listener)
                } finally {
                    withContext(NonCancellable) {
                        input.close()
                        microphone = null
                        job = null
                        if (!discardOutput) listener.onDone(failure)
                    }
                }
            }
        return true
    }

    fun stop() {
        microphone?.stop()
    }

    fun cancel() {
        if (job == null) return
        discardOutput = true
        microphone?.stop()
        job?.cancel()
    }

    /** Lets Govorun's process go once dictation is not expected soon. */
    fun release() {
        if (job == null) connection.release()
    }

    private suspend fun record(
        scope: CoroutineScope,
        input: MicrophoneCapture,
        profile: Int,
        listener: DictationListener,
    ): DictationFailure? {
        // The first words come while Govorun's process and model load, longer than a pipe can hold.
        val queue = Channel<ByteArray>(MAX_QUEUED_WINDOWS)
        val drain =
            scope.launch(Dispatchers.IO) {
                try {
                    for (window in input.capture.frames) {
                        if (queue.trySend(window).isFailure) Log.w(TAG, "audio queue full, window dropped")
                    }
                } finally {
                    queue.close()
                }
            }
        val service = connection.connect()
        if (service == null) {
            drain.cancel()
            return DictationFailure.UNAVAILABLE
        }
        val session = Session(service, scope, listener)
        var writer: Job? = null
        try {
            val pipe = session.open(profile) ?: return DictationFailure.UNAVAILABLE
            writer = scope.launch(Dispatchers.IO) { write(pipe, queue) }
            return session.outcome.await()
        } finally {
            withContext(NonCancellable) {
                // Closing Govorun's end of the pipe also releases a writer blocked in write().
                session.close()
                writer?.cancel()
                drain.cancel()
            }
        }
    }

    private suspend fun write(
        pipe: ParcelFileDescriptor,
        queue: Channel<ByteArray>,
    ) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { output ->
                for (window in queue) output.write(window)
            }
        } catch (failure: IOException) {
            // Govorun closed its end; its callback or the binder death tells why.
            Log.w(TAG, "audio stream closed by Govorun: ${failure.message}")
        }
    }

    /** One `recognizeContinuous` call: callbacks arrive on a Binder thread and go on to the main thread in order. */
    private inner class Session(
        private val service: IRecognitionService,
        private val scope: CoroutineScope,
        private val listener: DictationListener,
    ) {
        val outcome = CompletableDeferred<DictationFailure?>()
        private val id = UUID.randomUUID().toString()
        private val death = IBinder.DeathRecipient { outcome.complete(DictationFailure.UNAVAILABLE) }

        private val callback =
            object : IContinuousRecognitionCallback.Stub() {
                override fun onReady(sessionId: String?) {
                    onMain { listener.onReady() }
                }

                override fun onSpeechStateChanged(
                    sessionId: String?,
                    active: Boolean,
                ) {
                    onMain { listener.onSpeechActive(active) }
                }

                override fun onFinalResult(
                    sessionId: String?,
                    sequence: Long,
                    text: String?,
                ) {
                    if (text.isNullOrBlank() || discardOutput) return
                    onMain { listener.onSegment(text) }
                }

                override fun onStopped(sessionId: String?) {
                    onMain { outcome.complete(null) }
                }

                override fun onError(
                    sessionId: String?,
                    code: Int,
                    safeMessage: String?,
                ) {
                    Log.w(TAG, "Govorun reported error $code")
                    onMain { outcome.complete(GovorunApi.failure(code)) }
                }

                override fun onFinalResultTimed(
                    sessionId: String?,
                    sequence: Long,
                    text: String?,
                    wordStartsMs: LongArray?,
                    wordEndsMs: LongArray?,
                    words: Array<out String>?,
                ) = Unit

                override fun onPrimaryDictation(
                    sessionId: String?,
                    active: Boolean,
                    elapsedRealtimeMs: Long,
                ) = Unit
            }

        /** Starts the session and returns the end of the pipe to write to; null when Govorun refuses the call. */
        suspend fun open(profile: Int): ParcelFileDescriptor? =
            withContext(Dispatchers.IO) {
                val (read, write) = ParcelFileDescriptor.createPipe()
                try {
                    if (GovorunCalls.guard("recognizeContinuous") { start(read, profile) } == true) {
                        write
                    } else {
                        write.close()
                        null
                    }
                } finally {
                    // Govorun has its own copy now; ours would keep the pipe open after it closes.
                    read.close()
                }
            }

        /** False when this Govorun cannot take the session. */
        private fun start(
            read: ParcelFileDescriptor,
            profile: Int,
        ): Boolean {
            service.asBinder().linkToDeath(death, 0)
            if (!GovorunApi.supports(service.protocolVersion, service.capabilities)) return false
            service.recognizeContinuousWithProfile(
                context.packageName,
                id,
                read,
                GovorunApi.SAMPLE_RATE_HZ,
                GovorunApi.CHANNEL_COUNT,
                GovorunApi.BITS_PER_SAMPLE,
                profile,
                callback,
            )
            return true
        }

        suspend fun close() {
            withContext(Dispatchers.IO) {
                if (!outcome.isCompleted) runCatching { service.cancel(context.packageName, id) }
                runCatching { service.asBinder().unlinkToDeath(death, 0) }
            }
        }

        private fun onMain(block: suspend () -> Unit) {
            scope.launch(Dispatchers.Main) { block() }
        }
    }

    private companion object {
        const val TAG = "GovorunRecorder"

        // A minute of sound in 32 ms windows, far beyond a cold start; the bound only guards against a stuck Govorun.
        const val MAX_QUEUED_WINDOWS = 1_875
    }
}
