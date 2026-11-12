package ru.tinyops.android.vboard.voice

import android.content.Context
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ru.tinyops.android.vboard.core.input.CursorWindow
import ru.tinyops.android.vboard.core.voice.PromptPlace
import ru.tinyops.android.vboard.core.voice.TextFeature

/**
 * Connects [Proofreader] to the focused field and decides whether the strip shows its button:
 * proofreading is on and checked, the field is not a password and it has text. A hold on the button is
 * offered only while the button is idle and a prompt is chosen for the hold. Main thread only.
 */
internal class ProofreadControl(
    context: Context,
    private val connection: () -> InputConnection?,
    private val onButton: (ProofreadState?, Boolean) -> Unit,
    onMessage: (Int) -> Unit,
    private val results: PromptResultStore? = null,
) {
    private val prefs = VoicePrefs(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val enhancer = TextEnhancer()
    private var allowed = false
    private var fieldHasText = false

    private val proofreader =
        Proofreader(
            scope = scope,
            editor = ProofreadField(connection),
            enhance = { text -> proofread(text, held = false) },
            enhanceHeld = { text -> proofread(text, held = true) },
            onState = { publish() },
            onMessage = onMessage,
        )

    /** One request to the server; a successful answer goes to the buffer of prompt results. */
    private suspend fun proofread(
        text: String,
        held: Boolean,
    ): TextEnhancer.Result {
        val (server, effort) = prefs.connection(TextFeature.PROOFREAD, held)
        val answer = enhancer.enhance(server, text, effort)
        results?.recordAnswer(answer, prefs.prompts.library.tags(promptIds(held)))
        return answer
    }

    // The same choice as the request made: a hold without prompts of its own sends the default one.
    private fun promptIds(held: Boolean): List<String> {
        if (!held) return prefs.chosenPrompts(TextFeature.PROOFREAD)
        val own = prefs.prompts.ids(PromptPlace.PROOFREAD_HOLD)
        if (own != null &&
            prefs.prompts.library
                .compose(own)
                .isNotBlank()
        ) {
            return own
        }
        return prefs.prompts.ids(PromptPlace.ENHANCEMENT).orEmpty()
    }

    /** What the strip shows; a request or an undo outlives a field that became empty. */
    val button: ProofreadState?
        get() = proofreader.state.takeIf { allowed && (fieldHasText || it != ProofreadState.IDLE) }

    fun onStartInput(
        textField: Boolean,
        selectionEnd: Int,
    ) {
        proofreader.reset()
        allowed = textField && prefs.featureReady(TextFeature.PROOFREAD)
        holds = allowed && prefs.proofreadHoldReady
        onSelectionChanged(selectionEnd)
    }

    /**
     * Only with the cursor at the very start the editor has to be asked whether there is text, so
     * typing makes no extra calls across processes.
     */
    fun onSelectionChanged(selectionEnd: Int) {
        if (!allowed) return onButton(null, false)
        fieldHasText = selectionEnd > 0 || !connection()?.getTextAfterCursor(1, 0).isNullOrEmpty()
        publish()
    }

    private fun publish() = onButton(button, holds && button == ProofreadState.IDLE)

    /** Whether a hold on the button does something; read when the field starts. */
    var holds = false
        private set

    fun onTap() = proofreader.onTap()

    fun onHold() {
        if (!prefs.proofreadHoldReady) return
        proofreader.onHold()
    }

    /** What the field would hold after the undo, for the preview of a held undo arrow. */
    fun undoPreview(): String? = proofreader.undoPreview()

    fun reset() = proofreader.reset()

    fun destroy() = scope.cancel()
}

/** The focused field, reached through its [InputConnection]. */
internal class ProofreadField(
    private val connection: () -> InputConnection?,
) : Proofreader.Editor {
    override fun window(length: Int): CursorWindow? =
        connection()?.getSurroundingText(length, length, 0)?.let {
            CursorWindow(it.text, it.selectionStart, it.selectionEnd, it.offset)
        }

    override fun replace(
        start: Int,
        end: Int,
        replacement: String,
    ) {
        val ic = connection() ?: return
        ic.beginBatchEdit()
        ic.setSelection(start, end)
        ic.commitText(replacement, 1)
        ic.endBatchEdit()
    }
}
