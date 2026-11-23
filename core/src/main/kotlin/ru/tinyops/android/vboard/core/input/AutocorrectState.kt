package ru.tinyops.android.vboard.core.input

/**
 * Tracks a pending autocorrection for the word being typed and remembers the last applied
 * autocorrection so a single backspace reverts it to the user's original spelling.
 */
internal class AutocorrectState {
    private data class Applied(
        val original: String,
        val corrected: String,
        val trailing: String,
    )

    var pending: String? = null
        private set

    private var applied: Applied? = null

    fun setPending(word: String?) {
        pending = word
    }

    fun clearPending() {
        pending = null
    }

    fun clearUndo() {
        applied = null
    }

    fun reset() {
        pending = null
        applied = null
    }

    /**
     * Replaces the composing word with [pending] followed by [trailing] when an autocorrection is
     * pending, remembering the original word for backspace undo. Returns true when applied.
     */
    fun applyIfPending(
        editor: TextEditor,
        original: String,
        trailing: String,
        enabled: Boolean,
    ): Boolean {
        val target = pending
        pending = null
        if (!enabled || target == null || target == original) return false
        editor.commitText(target + trailing)
        applied = Applied(original = original, corrected = target, trailing = trailing)
        return true
    }

    /**
     * If the cursor stands right after the last applied autocorrection, replaces it with the
     * original spelling (without the trailing space or punctuation) and returns true.
     */
    fun undoIfMatching(editor: TextEditor): Boolean {
        val last = applied ?: return false
        applied = null
        val expected = last.corrected + last.trailing
        val before = editor.textBeforeCursor(expected.length)?.toString() ?: return false
        if (before != expected) return false
        editor.replaceAroundCursor(expected.length, 0, last.original)
        return true
    }

    companion object {
        private const val AUTOCORRECT_PUNCTUATION = ",.!?;:"

        fun triggersAutocorrect(text: String): Boolean = text.length == 1 && text[0] in AUTOCORRECT_PUNCTUATION
    }
}
