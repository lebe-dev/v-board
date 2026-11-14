package ru.tinyops.android.vboard.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.input.CursorWindow
import ru.tinyops.android.vboard.core.voice.ButtonAction
import ru.tinyops.android.vboard.core.voice.ButtonCommand
import ru.tinyops.android.vboard.core.voice.ButtonRequest
import ru.tinyops.android.vboard.core.voice.EnhancementOutcome
import ru.tinyops.android.vboard.core.voice.InsertMode
import ru.tinyops.android.vboard.core.voice.ProofreadTarget
import ru.tinyops.android.vboard.core.voice.ReplyInsertion
import ru.tinyops.android.vboard.diagnostics.Problems

/**
 * Runs the custom buttons of the strip. One button is at work at a time: while a request runs the
 * others only say so, and any gesture on it cancels it. A button that changed the text offers to undo it
 * until the user types again or uses another one. The text is compared before every change, so an editor that
 * changed in the meantime is not overwritten. A prompt command that asks for its prompt hands [choose] what to
 * do with the chosen one, and starts only once it is chosen. Holding the undo arrow only previews the text
 * as an undo would leave it; the undo itself is a tap. Main thread only.
 */
internal class ButtonActions(
    private val scope: CoroutineScope,
    private val editor: Editor,
    private val copy: (String) -> Unit,
    private val paste: () -> String?,
    private val enhance: suspend (ButtonCommand, String) -> TextEnhancer.Result,
    private val choose: (onChosen: (String) -> Unit) -> Unit,
    private val onState: () -> Unit,
    private val onMessage: (Int) -> Unit,
) {
    /** The focused field. */
    interface Editor : Proofreader.Editor {
        /** Removes all the text, also the part that a window does not reach. */
        fun clear()

        /** Puts [replacement] in place of the selection, or at the cursor, wherever the editor has them. */
        fun commit(replacement: String)
    }

    /** What the last action changed: [result] took the place of [original]. */
    private class Applied(
        val result: ProofreadTarget,
        val original: String,
    )

    /** The button at work, null when none is. */
    private var active: String? = null
    private var state = ProofreadState.IDLE
    private var request: Job? = null
    private var applied: Applied? = null

    /** Whether a button is running or can undo: the buttons stay even if the field became empty. */
    val busy: Boolean get() = active != null

    /** The button at work or offering its undo, null when none is. */
    val activeId: String? get() = active

    fun stateOf(id: String): ProofreadState = if (id == active) state else ProofreadState.IDLE

    /** A gesture on the button [id] whose [command] is what that gesture does; null means nothing. */
    fun trigger(
        id: String,
        command: ButtonCommand?,
    ) {
        if (!takeTurn(id, command != null)) return
        when (requireNotNull(command).action) {
            ButtonAction.CLEAR -> clear(id)
            ButtonAction.COPY -> copyText()
            ButtonAction.PASTE -> pasteText(id)
            ButtonAction.PROMPT -> if (command.pickPrompt) offer(id, command) else start(id, command)
            // The strip opens the list of replies itself, and a row of it comes to insertReply.
            ButtonAction.QUICK_REPLIES -> Unit
        }
    }

    /**
     * A tap on the row [id] of a quick replies list: [reply] goes into the field as [mode] says, and the same
     * row offers to undo it. An editor that does not tell where its text starts gets it without an undo.
     */
    fun insertReply(
        id: String,
        mode: InsertMode,
        reply: String,
    ) {
        if (!takeTurn(id, act = true)) return
        val window = readWindow() ?: return
        if (ReplyInsertion.tooLong(window, mode, READ_LENGTH)) {
            onMessage(R.string.proofread_too_long)
            return
        }
        val insertion = ReplyInsertion.of(window, mode, reply)
        if (insertion == null) {
            editor.commit(reply)
            return
        }
        val from = insertion.start - window.offset
        val original = window.text.substring(from, insertion.end - window.offset)
        editor.replace(insertion.start, insertion.end, insertion.text)
        applied = Applied(ProofreadTarget(insertion.start, insertion.text), original)
        show(id, ProofreadState.UNDO)
    }

    /**
     * Whether a gesture on the button [id] may go on to [act]: a gesture on the button at work cancels its
     * request or undoes its change instead, and the others wait for a running request.
     */
    private fun takeTurn(
        id: String,
        act: Boolean,
    ): Boolean {
        if (id == active) {
            if (state == ProofreadState.WORKING) reset() else undo()
            return false
        }
        if (!act) return false
        if (state == ProofreadState.WORKING) {
            onMessage(R.string.custom_button_busy)
            return false
        }
        reset()
        return true
    }

    /** Asks for the prompt only when there is something to send it with. */
    private fun offer(
        id: String,
        command: ButtonCommand,
    ) {
        val window = readWindow() ?: return
        if (ButtonRequest.tooLong(window, command.insert, Proofreader.MAX_CHARS_AROUND_CURSOR)) {
            onMessage(R.string.proofread_too_long)
            return
        }
        requestOf(window, command) ?: return
        choose { promptId ->
            // Another button may have started a request while the list was open.
            if (state == ProofreadState.WORKING) return@choose
            reset()
            start(id, command.withPrompt(promptId))
        }
    }

    /** The user went on typing or left the field: the request and the chance to undo are over. */
    fun reset() {
        request?.cancel()
        request = null
        applied = null
        show(null, ProofreadState.IDLE)
    }

    /**
     * The text of the field as an undo of the button [id] would leave it, for the preview shown while the undo
     * arrow is held; the field itself stays as it is. Null when the button has nothing to undo, or when the text
     * changed and the undo is given up.
     */
    fun undoPreview(id: String): String? {
        val last = applied
        if (last == null || !canUndo(id)) return null
        val window = editor.window(READ_LENGTH)
        if (window == null || !stillThere(last.result)) {
            giveUpUndo()
            return null
        }
        val from = last.result.start - window.offset
        return window.text.replaceRange(from, from + last.result.text.length, last.original).toString()
    }

    private fun canUndo(id: String) = id == active && state == ProofreadState.UNDO

    private fun giveUpUndo() {
        applied = null
        show(null, ProofreadState.IDLE)
        onMessage(R.string.proofread_changed)
    }

    private fun clear(id: String) {
        val window = readWindow() ?: return
        if (window.text.isEmpty()) return
        val whole = window.offset == 0 && !ProofreadTarget.tooLong(wholeOf(window), READ_LENGTH)
        editor.clear()
        if (!whole) return
        applied = Applied(ProofreadTarget(0, ""), window.text.toString())
        show(id, ProofreadState.UNDO)
    }

    private fun copyText() {
        val window = readWindow() ?: return
        if (ProofreadTarget.tooLong(window, READ_LENGTH)) {
            onMessage(R.string.proofread_too_long)
            return
        }
        val selected = window.selectionEnd > window.selectionStart
        val text = if (selected) window.text.subSequence(window.selectionStart, window.selectionEnd) else window.text
        if (text.isNotBlank()) copy(text.toString())
    }

    /**
     * Puts the text of the clipboard in place of the selection, or at the cursor; the button then offers an undo.
     * An editor that does not tell where its text starts gets the text without an undo, as positions would miss.
     */
    private fun pasteText(id: String) {
        val text = paste()
        if (text.isNullOrEmpty()) {
            onMessage(R.string.custom_button_clipboard_empty)
            return
        }
        val window = readWindow() ?: return
        if (window.offset < 0) {
            editor.commit(text)
            return
        }
        val start = window.offset + window.selectionStart
        val end = window.offset + window.selectionEnd
        val original = window.text.substring(window.selectionStart, window.selectionEnd)
        editor.replace(start, end, text)
        applied = Applied(ProofreadTarget(start, text), original)
        show(id, ProofreadState.UNDO)
    }

    private fun start(
        id: String,
        command: ButtonCommand,
    ) {
        val window = readWindow() ?: return
        if (ButtonRequest.tooLong(window, command.insert, Proofreader.MAX_CHARS_AROUND_CURSOR)) {
            onMessage(R.string.proofread_too_long)
            return
        }
        val target = requestOf(window, command) ?: return
        val before = ProofreadTarget(window.offset, window.text.toString())
        show(id, ProofreadState.WORKING)
        request =
            scope.launch {
                val answer = guardedRequest(target.text) { enhance(command, target.text) }
                finish(id, target, before, answer)
            }
    }

    private fun requestOf(
        window: CursorWindow,
        command: ButtonCommand,
    ): ButtonRequest? =
        ButtonRequest.of(window, command.insert).also {
            if (it == null) onMessage(R.string.custom_button_nothing_to_send)
        }

    private fun finish(
        id: String,
        target: ButtonRequest,
        before: ProofreadTarget,
        answer: TextEnhancer.Result,
    ) {
        request = null
        val problem =
            when (answer.outcome) {
                EnhancementOutcome.SUCCESS -> R.string.proofread_changed.takeUnless { stillThere(before) }
                EnhancementOutcome.UNCHANGED -> R.string.custom_button_no_changes
                EnhancementOutcome.AUTH_FAILED -> R.string.proofread_auth_failed
                EnhancementOutcome.FAILED -> R.string.proofread_failed
            }
        if (problem != null) {
            onMessage(problem)
            show(null, ProofreadState.IDLE)
            return
        }
        val inserted = target.replacement(answer.text)
        val original = before.text.substring(target.start - before.start, target.end - before.start)
        editor.replace(target.start, target.end, inserted)
        applied = Applied(ProofreadTarget(target.start, inserted), original)
        show(id, ProofreadState.UNDO)
    }

    private fun undo() {
        val last = applied
        applied = null
        show(null, ProofreadState.IDLE)
        if (last == null) return
        if (!stillThere(last.result)) {
            onMessage(R.string.proofread_changed)
            return
        }
        editor.replace(last.result.start, last.result.end, last.original)
    }

    /** Nothing can be found in a field by an empty text, only that the field is still empty. */
    private fun stillThere(target: ProofreadTarget): Boolean {
        val window = editor.window(READ_LENGTH) ?: return false
        return target.matches(window) && (target.text.isNotEmpty() || window.text.isEmpty())
    }

    private fun readWindow(): CursorWindow? {
        val window = editor.window(READ_LENGTH)
        if (window == null) {
            Problems.report(TAG, "The field did not give its text")
            onMessage(R.string.proofread_failed)
        }
        return window
    }

    private fun wholeOf(window: CursorWindow) =
        CursorWindow(window.text, window.selectionEnd, window.selectionEnd, window.offset)

    private fun show(
        id: String?,
        next: ProofreadState,
    ) {
        if (active == id && state == next) return
        active = id
        state = next
        onState()
    }

    private companion object {
        const val TAG = "ButtonActions"

        /** Reads further than the limit: an answer may be longer than the text it replaces. */
        const val READ_LENGTH = Proofreader.MAX_CHARS_AROUND_CURSOR * 4
    }
}
