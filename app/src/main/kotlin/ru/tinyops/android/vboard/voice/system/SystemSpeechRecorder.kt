package ru.tinyops.android.vboard.voice.system

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.core.voice.DictationFailure
import ru.tinyops.android.vboard.core.voice.SystemSpeech
import ru.tinyops.android.vboard.core.voice.VoiceLevel
import ru.tinyops.android.vboard.diagnostics.AppLog
import ru.tinyops.android.vboard.diagnostics.ErrorReporting
import ru.tinyops.android.vboard.voice.engine.DictationListener
import java.util.Locale

/**
 * Dictation through the phone's default [SpeechRecognizer]. It owns the microphone and ends after
 * every phrase, so listening starts again until [stop]; a few words can be lost in that gap.
 * Main thread only, as [SpeechRecognizer] requires.
 */
internal class SystemSpeechRecorder(
    context: Context,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var scope: CoroutineScope? = null
    private var listener: DictationListener? = null
    private var locale: Locale = Locale.getDefault()
    private var stopping = false
    private var busyRetries = 0

    private val listen = Runnable { startListening() }
    private val giveUp = Runnable { finish(null) }

    fun start(
        scope: CoroutineScope,
        locale: Locale,
        listener: DictationListener,
    ): Boolean {
        if (recognizer != null) return false
        val next =
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                .onFailure {
                    AppLog.error(TAG, "System recognizer could not be created", it)
                    ErrorReporting.capture(it)
                }.getOrNull() ?: return false
        next.setRecognitionListener(Callback())
        recognizer = next
        this.scope = scope
        this.listener = listener
        this.locale = locale
        stopping = false
        busyRetries = 0
        startListening()
        return true
    }

    /** Ends listening; what was said in the current phrase is still recognized. */
    fun stop() {
        val active = recognizer ?: return
        stopping = true
        if (main.hasCallbacks(listen)) {
            finish(null)
            return
        }
        active.stopListening()
        // Some recognizers never answer a stop after silence.
        main.postDelayed(giveUp, STOP_TIMEOUT_MILLIS)
    }

    /** Drops the recording without delivering anything. */
    fun cancel() {
        val active = recognizer ?: return
        active.cancel()
        close()
    }

    private fun startListening() {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        recognizer?.startListening(intent)
    }

    private fun finish(failure: DictationFailure?) {
        val done = listener
        val doneScope = scope
        close()
        if (done != null && doneScope != null) doneScope.launch { done.onDone(failure) }
    }

    private fun close() {
        main.removeCallbacks(listen)
        main.removeCallbacks(giveUp)
        recognizer?.destroy()
        recognizer = null
        listener = null
        scope = null
    }

    private inner class Callback : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            busyRetries = 0
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() {
            listener?.onSpeechActive(true)
        }

        override fun onEndOfSpeech() {
            listener?.onSpeechActive(false)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            val current = listener
            if (!text.isNullOrBlank() && current != null) scope?.launch { current.onSegment(text) }
            if (stopping) finish(null) else main.post(listen)
        }

        override fun onError(error: Int) {
            listener?.onSpeechActive(false)
            val failure = SystemSpeech.failure(error)
            when {
                stopping -> finish(failure.takeUnless { error == SpeechRecognizer.ERROR_CLIENT })
                failure == null -> main.post(listen)
                error == SystemSpeech.ERROR_BUSY && busyRetries < MAX_BUSY_RETRIES -> {
                    // The service has not let go of the previous phrase yet.
                    busyRetries++
                    main.postDelayed(listen, BUSY_RETRY_MILLIS)
                }
                else -> {
                    Log.w(TAG, "System recognizer reported error $error")
                    finish(failure)
                }
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            listener?.onLevel(VoiceLevel.fromRmsDb(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private companion object {
        const val TAG = "SystemSpeechRecorder"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val BUSY_RETRY_MILLIS = 150L
        const val MAX_BUSY_RETRIES = 3
    }
}
