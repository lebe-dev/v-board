package ru.tinyops.android.vboard.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.AutoStop
import ru.tinyops.android.vboard.core.voice.DictationFailure
import ru.tinyops.android.vboard.core.voice.DictationPlan
import ru.tinyops.android.vboard.core.voice.DictationResults
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.FeatureConnection
import ru.tinyops.android.vboard.core.voice.GovorunApi
import ru.tinyops.android.vboard.core.voice.Improvement
import ru.tinyops.android.vboard.core.voice.PromptTag
import ru.tinyops.android.vboard.core.voice.RecognitionEngine
import ru.tinyops.android.vboard.core.voice.ServerCheck
import ru.tinyops.android.vboard.core.voice.Transcript
import ru.tinyops.android.vboard.core.voice.VoiceDictionary
import ru.tinyops.android.vboard.voice.engine.DictationListener
import ru.tinyops.android.vboard.voice.engine.VadRecorder
import ru.tinyops.android.vboard.voice.govorun.GovorunConnection
import ru.tinyops.android.vboard.voice.govorun.GovorunRecorder
import ru.tinyops.android.vboard.voice.system.SystemSpeechRecorder
import java.util.Locale

/** What the voice strip shows. */
data class VoiceState(
    val phase: Phase = Phase.IDLE,
    /** The VAD hears speech right now. */
    val speech: Boolean = false,
    /** Govorun is still loading; the microphone already records. */
    val preparing: Boolean = false,
    /** Recognized text waiting for the second tap ("Повторным нажатием"). */
    val preview: String = "",
) {
    enum class Phase {
        IDLE,
        LISTENING,
        RECOGNIZING,
        ENHANCING,
    }

    val active: Boolean get() = phase != Phase.IDLE
}

/**
 * Dictation in the keyboard: a tap on the microphone, or a mode of the menu its hold opens, starts
 * recording, the next tap stops it, × cancels. Recognition is the phone's default recognizer
 * ([SystemSpeechRecorder]), the installed Govorun app when chosen in the settings ([GovorunRecorder])
 * or, when "Распознавание на сервере" is on, the local VAD ([VadRecorder]) with every phrase sent to
 * the user's [RemoteTranscriber]. The recognized text goes through the dictionary and, when the mode
 * asks for it, the user's text improvement server ([EnhancementQueue]) before it reaches the editor,
 * in any recording mode and with any recognizer.
 * Main thread only.
 */
class VoiceInput(
    context: Context,
    private val host: Host,
    private val results: PromptResultStore? = null,
) {
    interface Host {
        fun textBeforeDictation(): CharSequence?

        /** The language of the current layout, for the system recognizer. */
        fun dictationLocale(): Locale

        /** Inserts dictated text at the cursor (after committing any composing word). */
        fun insertText(text: String)

        fun onVoiceState(state: VoiceState)

        fun setKeepScreenOn(keep: Boolean)

        /** Opens the settings screen, asking for the microphone permission when [requestPermission]. */
        fun openSettings(requestPermission: Boolean)

        fun showMessage(
            @StringRes message: Int,
        )
    }

    private val context = context.applicationContext
    private val prefs = VoicePrefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val serverRecorder = VadRecorder(this.context)
    private val govorunRecorder = GovorunRecorder(this.context)
    private val systemRecorder = SystemSpeechRecorder(this.context)
    private val enhancer = TextEnhancer()

    var state = VoiceState()
        private set(value) {
            field = value
            host.onVoiceState(value)
        }

    private var transcript: Transcript? = null
    private var dictionary = VoiceDictionary.EMPTY
    private var enhancements: EnhancementQueue? = null
    private var enhancementDrain: Job? = null

    /** The pieces of the dictation that goes through text improvement, for the buffer of prompt results. */
    private var dictationResults: DictationResults? = null
    private var dictationPrompts: List<PromptTag> = emptyList()

    private val autoStop =
        Runnable {
            if (state.phase == VoiceState.Phase.LISTENING) {
                host.showMessage(R.string.voice_auto_stopped)
                stop()
            }
        }
    private val releaseEngine =
        Runnable {
            if (!state.active) releaseEngines()
        }

    /** The microphone key: starts a dictation the way a tap does, or ends the current one. */
    fun toggle() {
        when (state.phase) {
            VoiceState.Phase.IDLE -> start(prefs.micTapPlan())
            VoiceState.Phase.LISTENING -> stop()
            VoiceState.Phase.RECOGNIZING, VoiceState.Phase.ENHANCING -> Unit
        }
    }

    /** The mode [id] of the hold menu: starts a dictation the way it says, unless one is going on. */
    fun dictate(id: String) {
        if (state.phase != VoiceState.Phase.IDLE) return
        prefs.micHoldChoices().firstOrNull { it.mode.id == id }?.let { start(it.plan) }
    }

    /** × : drops the recording (or the improvement request) and everything not inserted yet. */
    fun cancel() {
        when (state.phase) {
            VoiceState.Phase.IDLE -> return
            VoiceState.Phase.LISTENING, VoiceState.Phase.RECOGNIZING -> {
                serverRecorder.cancel()
                govorunRecorder.cancel()
                systemRecorder.cancel()
            }
            VoiceState.Phase.ENHANCING -> enhancementDrain?.cancel()
        }
        transcript?.discard()
        finish()
    }

    /** The keyboard was hidden: the text would have nowhere to go. */
    fun onHidden() {
        cancel()
        scheduleRelease()
    }

    fun destroy() {
        cancel()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        releaseEngines()
    }

    private fun start(plan: DictationPlan) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.openSettings(requestPermission = true)
            return
        }
        val remote = prefs.remoteReady
        val engine = prefs.engine
        if (!remote && engine == RecognitionEngine.GOVORUN && !GovorunConnection.isInstalled(context)) {
            host.showMessage(R.string.voice_govorun_missing)
            return
        }
        if (!remote && engine == RecognitionEngine.SYSTEM && !SpeechRecognizer.isRecognitionAvailable(context)) {
            host.showMessage(R.string.voice_system_unavailable)
            return
        }
        main.removeCallbacks(releaseEngine)
        val mode = plan.mode
        val next = Transcript(mode.deferred)
        dictionary = prefs.dictionary()
        val listener = Listener(next, remote)
        val started =
            if (remote) {
                serverRecorder.start(
                    scope = scope,
                    headset = prefs.headsetMicrophone,
                    minSilenceSeconds = prefs.pause.seconds(atEnd = mode.deferred),
                    transcriber = RemoteTranscriber(prefs.remoteServer),
                    listener = listener,
                )
            } else if (engine == RecognitionEngine.GOVORUN) {
                govorunRecorder.start(
                    scope = scope,
                    headset = prefs.headsetMicrophone,
                    profile = GovorunApi.profile(prefs.pause),
                    listener = listener,
                )
            } else {
                systemRecorder.start(scope = scope, locale = host.dictationLocale(), listener = listener)
            }
        if (!started) {
            host.showMessage(R.string.voice_microphone_unavailable)
            return
        }
        transcript = next
        enhancements = prefs.improvementConnection(plan.improvement)?.let(::newEnhancementQueue)
        startResults(plan.improvement)
        state = VoiceState(VoiceState.Phase.LISTENING, preparing = !remote && engine == RecognitionEngine.GOVORUN)
        if (prefs.keepScreenOn) host.setKeepScreenOn(true)
        AutoStop
            .limitMillis(
                prefs.keepScreenOn,
                prefs.autoStop,
            ).takeIf { it > 0 }
            ?.let { main.postDelayed(autoStop, it) }
    }

    private fun stop() {
        main.removeCallbacks(autoStop)
        serverRecorder.stop()
        govorunRecorder.stop()
        systemRecorder.stop()
        state = state.copy(phase = VoiceState.Phase.RECOGNIZING, speech = false)
    }

    private fun releaseEngines() {
        govorunRecorder.release()
        VadRecorder.release()
    }

    private inner class Listener(
        private val transcript: Transcript,
        private val remote: Boolean,
    ) : DictationListener {
        private val current get() = transcript === this@VoiceInput.transcript && state.active

        // One message per dictation: a server that is down would fail every phrase.
        private var failureShown = false

        override fun onReady() {
            if (current && state.preparing) state = state.copy(preparing = false)
        }

        override suspend fun onSegment(text: String) {
            if (!current) return
            transcript.accept(text)?.let { deliver(dictionary.apply(it)) }
            if (transcript.deferred) state = state.copy(preview = transcript.pending)
        }

        override suspend fun onSegmentFailed(failure: Exception) {
            // A lost phrase on the server must be visible, once per dictation.
            if (!current || !remote || failureShown) return
            failureShown = true
            val status = (failure as? ServerHttpException)?.status
            val auth = status != null && ServerCheck.failure(status) == ServerCheck.AUTH_FAILED
            host.showMessage(if (auth) R.string.voice_remote_auth_failed else R.string.voice_remote_failed)
        }

        override fun onSpeechActive(active: Boolean) {
            if (current && state.phase == VoiceState.Phase.LISTENING) state = state.copy(speech = active)
        }

        override suspend fun onDone(failure: DictationFailure?) {
            if (!current) return
            failure?.let { host.showMessage(it.message()) }
            transcript
                .complete()
                ?.let(dictionary::apply)
                ?.takeIf(String::isNotBlank)
                ?.let(::deliver)
            val queue = enhancements
            if (queue == null || queue.idle) {
                finish()
                return
            }
            state = state.copy(phase = VoiceState.Phase.ENHANCING, speech = false)
            enhancementDrain =
                scope.launch {
                    queue.finish()
                    finish()
                }
        }
    }

    private fun newEnhancementQueue(connection: FeatureConnection): EnhancementQueue {
        val (server, effort) = connection
        val threshold = prefs.enhancementThreshold
        return EnhancementQueue(
            scope = scope,
            enhance = { enhancer.enhance(server, it, effort) },
            onFailure = {
                val auth = it == EnhancementOutcome.AUTH_FAILED
                host.showMessage(
                    if (auth) R.string.voice_enhancement_auth_failed else R.string.voice_enhancement_failed,
                )
            },
            insert = ::insert,
            shouldEnhance = threshold::applies,
            onInserted = { recognized, inserted, improved ->
                dictationResults?.add(recognized, inserted, improved)
            },
        )
    }

    /** A dictation that goes through text improvement is kept in the buffer of prompt results, when it is on. */
    private fun startResults(improvement: Improvement) {
        val kept = enhancements != null && prefs.promptResultsEnabled
        dictationResults = if (kept) DictationResults() else null
        dictationPrompts =
            if (kept) prefs.prompts.library.tags(prefs.improvementPromptIds(improvement)) else emptyList()
    }

    /** Recognized text on its way to the editor: straight there, or through text improvement. */
    private fun deliver(text: String) {
        val queue = enhancements
        if (queue == null) insert(text) else queue.submit(text)
    }

    private fun insert(text: String) {
        if (text.isEmpty()) return
        host.insertText(Transcript.withLeadingSpace(text, host.textBeforeDictation()))
    }

    private fun finish() {
        main.removeCallbacks(autoStop)
        enhancements?.cancel()
        enhancements = null
        dictationResults?.texts()?.let { results?.recordDictation(it, dictationPrompts) }
        dictationResults = null
        enhancementDrain = null
        transcript = null
        host.setKeepScreenOn(false)
        if (state.active) state = VoiceState()
        scheduleRelease()
    }

    /** The engines hold native memory: keep them only while dictation is likely to continue. */
    private fun scheduleRelease() {
        main.removeCallbacks(releaseEngine)
        main.postDelayed(releaseEngine, ENGINE_IDLE_MILLIS)
    }

    @StringRes
    private fun DictationFailure.message(): Int =
        when (this) {
            DictationFailure.VAD -> R.string.voice_vad_failed
            DictationFailure.UNAVAILABLE -> R.string.voice_govorun_unavailable
            DictationFailure.UNAUTHORIZED -> R.string.voice_govorun_access
            DictationFailure.MODEL_NOT_READY -> R.string.voice_govorun_model
            DictationFailure.BUSY -> R.string.voice_govorun_busy
            DictationFailure.RECOGNITION -> R.string.voice_govorun_failed
            DictationFailure.SYSTEM_UNAVAILABLE -> R.string.voice_system_unavailable
            DictationFailure.SYSTEM_NETWORK -> R.string.voice_system_network
            DictationFailure.SYSTEM_LANGUAGE -> R.string.voice_system_language
            DictationFailure.SYSTEM_FAILED -> R.string.voice_system_failed
        }

    private companion object {
        const val ENGINE_IDLE_MILLIS = 120_000L
    }
}
