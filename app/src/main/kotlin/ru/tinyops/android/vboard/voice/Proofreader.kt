package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.CursorWindow
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.ProofreadTarget
import ru.tinyops.android.vboard.diagnostics.Problems

/** What the proofread button of the strip does on the next tap. */
enum class ProofreadState {
    IDLE,
    WORKING,
    UNDO,
}

/**
 * "Вычитка": on a tap the selection, or the whole text of the field, goes to the server and the
 * answer replaces it; the next tap brings the original back until the user types again. The text
 * is compared before every replacement, so an editor that changed in the meantime is not
 * overwritten. A hold does the same with the hold prompt, and does nothing without one. Main thread only.
 */
internal class Proofreader(
    private val scope: CoroutineScope,
    private val editor: Editor,
    private val enhance: suspend (String) -> TextEnhancer.Result,
    private val enhanceHeld: (suspend (String) -> TextEnhancer.Result)? = null,
    private val onState: (ProofreadState) -> Unit,
    private val onMessage: (Int) -> Unit,
) {
    /** The focused field. */
    interface Editor {
        /** The text around the cursor, up to [length] characters on each side. */
        fun window(length: Int): CursorWindow?

        fun replace(
            start: Int,
            end: Int,
            replacement: String,
        )
    }

    /** What the last answer replaced. */
    private class Applied(
        val original: String,
        val result: ProofreadTarget,
    )

    var state = ProofreadState.IDLE
        private set(value) {
            if (field == value) return
            field = value
            onState(value)
        }

    private var request: Job? = null
    private var applied: Applied? = null

    fun onTap() {
        when (state) {
            ProofreadState.IDLE -> start(enhance)
            ProofreadState.WORKING -> reset()
            ProofreadState.UNDO -> undo()
        }
    }

    /** A hold: proofreads with the hold prompt; while a request runs or can be undone it acts like a tap. */
    fun onHold() {
        if (state != ProofreadState.IDLE) return onTap()
        start(enhanceHeld ?: return)
    }

    /**
     * The text of the field as an undo would leave it, for the preview of a held undo arrow; the field stays as
     * it is. Null without an undo, or when the text changed and the undo is given up.
     */
    fun undoPreview(): String? {
        val last = applied ?: return null
        val window = editor.window(READ_LENGTH)
        if (window == null || !last.result.matches(window)) {
            reset()
            onMessage(R.string.proofread_changed)
            return null
        }
        val from = last.result.start - window.offset
        return window.text.replaceRange(from, from + last.result.text.length, last.original).toString()
    }

    /** The user went on typing or left the field: the request and the chance to undo are over. */
    fun reset() {
        request?.cancel()
        request = null
        applied = null
        state = ProofreadState.IDLE
    }

    private fun start(send: suspend (String) -> TextEnhancer.Result) {
        val window = editor.window(READ_LENGTH)
        if (window == null) {
            Problems.report(TAG, "The field did not give its text, so there is nothing to proofread")
            onMessage(R.string.proofread_failed)
            return
        }
        if (ProofreadTarget.tooLong(window, MAX_CHARS_AROUND_CURSOR)) {
            onMessage(R.string.proofread_too_long)
            return
        }
        val target = ProofreadTarget.of(window)
        if (target == null) {
            onNothingToSend(window)
            return
        }
        state = ProofreadState.WORKING
        request = scope.launch { finish(target, guardedRequest(target.text) { send(target.text) }) }
    }

    /** An editor that does not tell where its text starts is a failure; a blank field only has nothing to fix. */
    private fun onNothingToSend(window: CursorWindow) {
        if (window.offset >= 0) return onMessage(R.string.proofread_no_changes)
        Problems.report(TAG, "The field does not tell where its text starts, so it cannot be replaced")
        onMessage(R.string.proofread_failed)
    }

    private fun finish(
        target: ProofreadTarget,
        answer: TextEnhancer.Result,
    ) {
        request = null
        val problem =
            when (answer.outcome) {
                EnhancementOutcome.SUCCESS -> R.string.proofread_changed.takeUnless { stillThere(target) }
                EnhancementOutcome.UNCHANGED -> R.string.proofread_no_changes
                EnhancementOutcome.AUTH_FAILED -> R.string.proofread_auth_failed
                EnhancementOutcome.FAILED -> R.string.proofread_failed
            }
        if (problem != null) {
            onMessage(problem)
            state = ProofreadState.IDLE
            return
        }
        editor.replace(target.start, target.end, answer.text)
        applied = Applied(target.text, ProofreadTarget(target.start, answer.text))
        state = ProofreadState.UNDO
    }

    private fun undo() {
        val last = applied
        applied = null
        state = ProofreadState.IDLE
        if (last == null) return
        if (!stillThere(last.result)) {
            onMessage(R.string.proofread_changed)
            return
        }
        editor.replace(last.result.start, last.result.end, last.original)
    }

    private fun stillThere(target: ProofreadTarget): Boolean = editor.window(READ_LENGTH)?.let(target::matches) == true

    companion object {
        private const val TAG = "Proofreader"

        /** Past this many characters on a side of the cursor the whole text is too long to send. */
        const val MAX_CHARS_AROUND_CURSOR = 16_000

        /** Reads further than the limit: an answer may be longer than the text it replaces. */
        private const val READ_LENGTH = MAX_CHARS_AROUND_CURSOR * 4
    }
}
