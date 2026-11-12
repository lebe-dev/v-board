package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome

/**
 * The text improvement step of one dictation. It runs after recognition, whatever recognized the
 * text: pieces go to the server one at a time and are inserted in the order they were dictated,
 * while the recording goes on. A failed request inserts the recognized text and is reported once,
 * because a server that is down would fail every phrase. A piece that [shouldEnhance] turns down is
 * inserted as it is: at once when nothing is waiting, otherwise in its turn. [onInserted] learns
 * every inserted piece with the text it came from and whether the model improved it. Main thread only.
 */
internal class EnhancementQueue(
    scope: CoroutineScope,
    private val enhance: suspend (String) -> TextEnhancer.Result,
    private val onFailure: (EnhancementOutcome) -> Unit,
    private val insert: (String) -> Unit,
    private val shouldEnhance: (String) -> Boolean = { true },
    private val onInserted: (recognized: String, inserted: String, improved: Boolean) -> Unit = { _, _, _ -> },
) {
    private val texts = Channel<String>(Channel.UNLIMITED)
    private var pending = 0
    private var failureReported = false

    private val worker =
        scope.launch {
            for (text in texts) {
                if (shouldEnhance(text)) improve(text) else deliver(text, text, improved = false)
                pending--
            }
        }

    /** Whether every submitted piece has been inserted. */
    val idle: Boolean get() = pending == 0

    fun submit(text: String) {
        if (idle && !shouldEnhance(text)) {
            deliver(text, text, improved = false)
            return
        }
        pending++
        texts.trySend(text)
    }

    /** Accepts no more text and waits until everything submitted is inserted. */
    suspend fun finish() {
        texts.close()
        worker.join()
    }

    /** Drops everything not inserted yet, including the request in progress. */
    fun cancel() {
        texts.cancel()
        worker.cancel()
    }

    private suspend fun improve(text: String) {
        val result = enhance(text)
        report(result.outcome)
        deliver(text, result.text, improved = result.outcome == EnhancementOutcome.SUCCESS)
    }

    private fun deliver(
        recognized: String,
        text: String,
        improved: Boolean,
    ) {
        insert(text)
        onInserted(recognized, text, improved)
    }

    private fun report(outcome: EnhancementOutcome) {
        if (outcome != EnhancementOutcome.FAILED && outcome != EnhancementOutcome.AUTH_FAILED) return
        if (failureReported) return
        failureReported = true
        onFailure(outcome)
    }
}
