package ru.tinyops.android.vboard.voice

import android.view.inputmethod.InputConnection
import ru.tinyops.android.vboard.R
import ru.tinyops.android.vboard.core.voice.DictationUndo

/**
 * Connects [DictationUndo] to the focused field: the strip offers the undo once a dictation that inserted text is
 * over, and a tap removes that text from before the cursor, unless the field changed. While dictating the record
 * goes on whatever happens in the field: a change made meanwhile is caught when the text is checked. Main thread only.
 */
internal class DictationUndoControl(
    private val connection: () -> InputConnection?,
    private val onOffer: (Boolean) -> Unit,
    private val onMessage: (Int) -> Unit,
) {
    private val undo = DictationUndo()
    private var dictating = false

    /** Follows the voice input: a dictation starting begins a new record, its end offers the undo. */
    fun onVoiceActive(active: Boolean) {
        if (active == dictating) return
        dictating = active
        if (active) undo.start() else undo.finish()
        publish()
    }

    fun add(text: String) = undo.add(text)

    fun reset() {
        if (dictating) return
        if (!undo.available) return undo.reset()
        undo.reset()
        publish()
    }

    /** A cursor moved away from the dictated text gives the undo up. */
    fun onSelectionChanged() {
        if (!undo.available) return
        if (undo.lengthBefore(textBefore()) == null) reset()
    }

    fun onTap() {
        val length = undo.lengthBefore(textBefore())
        val ic = connection()
        if (length == null || ic == null) {
            reset()
            onMessage(R.string.voice_undo_changed)
            return
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.endBatchEdit()
        reset()
    }

    private fun textBefore(): CharSequence? = connection()?.getTextBeforeCursor(undo.length, 0)

    private fun publish() = onOffer(undo.available)
}
