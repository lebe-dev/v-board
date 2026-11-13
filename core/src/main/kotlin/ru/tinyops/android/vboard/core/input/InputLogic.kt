package ru.tinyops.android.vboard.core.input

import java.util.Locale

/** The editing operations the input logic needs; implemented over an `InputConnection`. */
interface TextEditor {
    fun textBeforeCursor(length: Int): CharSequence?

    fun textAfterCursor(length: Int): CharSequence?

    /** Replaces [before] characters before the cursor and [after] behind it with [text]; the cursor ends after it. */
    fun replaceAroundCursor(
        before: Int,
        after: Int,
        text: CharSequence,
    )

    fun commitText(text: CharSequence)

    /** Replaces the current composing region (or inserts at the cursor) with [text]. */
    fun setComposingText(text: CharSequence)

    fun finishComposingText()

    /** Sends a backspace key event (deletes the selection or the character before the cursor). */
    fun sendBackspace()

    /** Performs the editor action, or inserts a newline when there is none. */
    fun sendEnter()

    fun beginBatchEdit()

    fun endBatchEdit()
}

/**
 * Editing logic of the keyboard, independent of Android.
 *
 *  - The word being typed (or the last swiped word) is kept as composing text so that a
 *    suggestion can replace it; space, punctuation, enter or a cursor move commit it.
 *  - After a swiped word a "phantom space" is pending: the next letter or swipe inserts a
 *    space first, punctuation does not.
 *  - A swiped word is deleted by backspace as a whole and can be swapped for an alternative.
 *
 * @param completions prefix completions (lowercase prefix -> words, most likely first)
 * @param onSuggestions receives the suggestions to display, primary first
 * @param corrections better spellings of a lowercase word the cursor stands on, most likely first; empty when the
 *   word is right or nothing is close
 */
class InputLogic(
    private val completions: (String) -> List<String>,
    private val onSuggestions: (List<String>) -> Unit,
    private val corrections: (String) -> List<String> = { emptyList() },
) {
    val shift = ShiftController()
    var locale: Locale = Locale.ENGLISH

    /** False for passwords and editors that opt out of suggestions: text is committed directly. */
    var suggestionsAllowed: Boolean = true

    private val composing = StringBuilder()
    private var composingIsGesture = false
    private var phantomSpace = false

    /** The word under the cursor that the shown corrections are for, null when none are shown. */
    private var misspelled: WordAtCursor? = null
    private var lastSpaceAt = Long.MIN_VALUE

    val composingText: String get() = composing.toString()

    val isComposing: Boolean get() = composing.isNotEmpty()

    fun typeCharacter(
        editor: TextEditor,
        raw: String,
    ) {
        val isWordCharacter = TextRules.isWordCharacter(raw)
        val text = if (isWordCharacter) TextRules.applyShift(raw, shift.state, locale) else raw
        // One batch, so the editor reports a single, consistent selection update.
        editor.beginBatchEdit()
        if (composingIsGesture) commitComposing(editor)
        if (isWordCharacter) {
            if (phantomSpace && composing.isEmpty()) editor.commitText(" ")
            phantomSpace = false
            if (suggestionsAllowed) {
                composing.append(text)
                editor.setComposingText(composing)
                showCompletions()
            } else {
                editor.commitText(text)
            }
            shift.onLetterTyped()
        } else {
            commitComposing(editor)
            phantomSpace = false
            editor.commitText(text)
        }
        editor.endBatchEdit()
    }

    fun typeSpace(
        editor: TextEditor,
        nowMillis: Long,
    ) {
        editor.beginBatchEdit()
        commitComposing(editor)
        phantomSpace = false
        val quickSecondSpace = lastSpaceAt != Long.MIN_VALUE && nowMillis - lastSpaceAt < DOUBLE_SPACE_MILLIS
        if (quickSecondSpace && TextRules.isDoubleSpacePeriod(editor.textBeforeCursor(2))) {
            editor.replaceAroundCursor(1, 0, ". ")
            lastSpaceAt = Long.MIN_VALUE
        } else {
            editor.commitText(" ")
            lastSpaceAt = nowMillis
        }
        editor.endBatchEdit()
    }

    fun typeEnter(editor: TextEditor) {
        commitComposing(editor)
        phantomSpace = false
        editor.sendEnter()
    }

    fun backspace(editor: TextEditor) {
        phantomSpace = false
        when {
            composingIsGesture -> {
                editor.setComposingText("")
                resetComposing()
            }
            composing.isNotEmpty() -> {
                composing.setLength(composing.length - 1)
                editor.setComposingText(composing)
                showCompletions()
            }
            else -> editor.sendBackspace()
        }
    }

    /** One step of the delete key: a character, a word or the whole text. */
    fun deleteBy(
        editor: TextEditor,
        mode: DeleteHoldMode,
    ) {
        when (mode) {
            DeleteHoldMode.CHARACTERS -> backspace(editor)
            DeleteHoldMode.WORDS -> deleteWord(editor)
            DeleteHoldMode.ALL -> deleteAll(editor)
            DeleteHoldMode.NONE -> Unit
        }
    }

    /** Deletes the word before the cursor with the spaces after it; a word being typed goes as a whole. */
    private fun deleteWord(editor: TextEditor) {
        phantomSpace = false
        if (composing.isNotEmpty()) {
            editor.setComposingText("")
            resetComposing()
            return
        }
        val length = TextRules.wordBeforeLength(editor.textBeforeCursor(WORD_LOOKBACK))
        if (length > 0) editor.replaceAroundCursor(length, 0, "")
    }

    /** Deletes the selection and up to [CLEAR_LOOKAROUND] characters on each side of it. */
    private fun deleteAll(editor: TextEditor) {
        phantomSpace = false
        editor.beginBatchEdit()
        editor.finishComposingText()
        resetComposing()
        val before = editor.textBeforeCursor(CLEAR_LOOKAROUND)?.length ?: 0
        val after = editor.textAfterCursor(CLEAR_LOOKAROUND)?.length ?: 0
        editor.replaceAroundCursor(before, after, "")
        editor.endBatchEdit()
    }

    fun pickSuggestion(
        editor: TextEditor,
        word: String,
    ) {
        when {
            misspelled != null && composing.isEmpty() -> replaceMisspelled(editor, word)
            composingIsGesture -> {
                // Swap the swiped word for an alternative; it stays replaceable.
                composing.setLength(0)
                composing.append(word)
                editor.setComposingText(word)
            }
            composing.isNotEmpty() -> {
                editor.commitText("$word ")
                resetComposing()
            }
        }
    }

    /** A gesture started: whatever is being composed becomes final. */
    fun startGesture(editor: TextEditor) {
        commitComposing(editor)
    }

    /**
     * Inserts the best of the decoded [words] (lowercase, best first), separated from the
     * previous word by a space, and offers the rest as alternatives.
     */
    fun commitGesture(
        editor: TextEditor,
        words: List<String>,
    ) {
        val candidates = casedForShift(words)
        val word = candidates.firstOrNull() ?: return
        editor.beginBatchEdit()
        commitComposing(editor)
        if (TextRules.needsSpaceBefore(editor.textBeforeCursor(1))) editor.commitText(" ")
        if (suggestionsAllowed) {
            composing.append(word)
            composingIsGesture = true
            editor.setComposingText(word)
            onSuggestions(candidates)
        } else {
            editor.commitText(word)
        }
        editor.endBatchEdit()
        phantomSpace = true
        shift.onLetterTyped()
    }

    /** Live suggestions for a gesture still in progress. */
    fun showGesturePreview(words: List<String>) = onSuggestions(casedForShift(words))

    /**
     * Reacts to a selection change reported by the editor. Updates without a composing region
     * (`candidatesEnd == -1`) may be stale replies to earlier edits and are ignored; a cursor
     * outside the composing region commits it.
     */
    fun onSelectionChanged(
        editor: TextEditor,
        selectionStart: Int,
        selectionEnd: Int,
        candidatesEnd: Int,
        correct: Boolean = true,
    ) {
        val movedAway = candidatesEnd != -1 && (selectionStart != candidatesEnd || selectionEnd != candidatesEnd)
        if (composing.isNotEmpty() && movedAway) {
            commitComposing(editor)
            phantomSpace = false
        }
        when {
            selectionStart != selectionEnd -> dropCorrections()
            correct -> showCorrections(editor)
        }
    }

    /**
     * Offers better spellings while the cursor, with nothing selected, stands in or at the end of a word that is
     * not in the dictionary; otherwise takes them away. Not for a word being composed, which has completions.
     */
    fun showCorrections(editor: TextEditor) {
        if (composing.isNotEmpty()) {
            // The completions of the word being typed are on the strip now; they are not ours to take away.
            misspelled = null
            return
        }
        val word = if (suggestionsAllowed) wordAtCursor(editor) else null
        val better = word?.let { corrections(it.text.lowercase(locale)) }.orEmpty()
        if (word == null || better.isEmpty()) return dropCorrections()
        misspelled = word
        onSuggestions(better.map { TextRules.matchCase(it, word.text, locale) }.distinct().take(SUGGESTIONS))
    }

    private fun dropCorrections() {
        if (misspelled == null) return
        misspelled = null
        onSuggestions(emptyList())
    }

    private fun replaceMisspelled(
        editor: TextEditor,
        word: String,
    ) {
        val target = misspelled ?: return
        misspelled = null
        editor.replaceAroundCursor(target.before, target.after, word)
        onSuggestions(emptyList())
    }

    private fun wordAtCursor(editor: TextEditor): WordAtCursor? {
        val before =
            editor
                .textBeforeCursor(MAX_WORD)
                ?.takeLastWhile(::isInWord)
                ?.toString()
                .orEmpty()
        val after =
            editor
                .textAfterCursor(MAX_WORD)
                ?.takeWhile(::isInWord)
                ?.toString()
                .orEmpty()
        val text = before + after
        if (text.length < MIN_CORRECTED || text.length >= MAX_WORD) return null
        if (text.first() == '-' || text.last() == '-') return null
        return WordAtCursor(text, before.length, after.length)
    }

    private fun isInWord(c: Char) = c.isLetter() || c == '-'

    private class WordAtCursor(
        val text: String,
        val before: Int,
        val after: Int,
    )

    /** Commits the composing text, if any. */
    fun commitComposing(editor: TextEditor) {
        if (composing.isEmpty()) return
        editor.finishComposingText()
        resetComposing()
    }

    /** The finger is about to move the cursor: what was typed is final and a next space starts afresh. */
    fun startCursorMove(editor: TextEditor) {
        commitComposing(editor)
        phantomSpace = false
        lastSpaceAt = Long.MIN_VALUE
    }

    /** Forgets all state, e.g. when a new editor gets focus. */
    fun reset() {
        resetComposing()
        phantomSpace = false
        lastSpaceAt = Long.MIN_VALUE
    }

    /** Recomputes completions for the word being typed (e.g. once a dictionary is loaded). */
    fun refreshCompletions() {
        if (!composingIsGesture) showCompletions()
    }

    private fun showCompletions() {
        if (composing.isEmpty() || !suggestionsAllowed) {
            onSuggestions(emptyList())
            return
        }
        val typed = composing.toString()
        val words = completions(typed.lowercase(locale)).map { TextRules.matchCase(it, typed, locale) }
        // Primary slot: the most likely completion; the literal typed text comes second.
        onSuggestions((listOf(words.firstOrNull() ?: typed, typed) + words).distinct().take(SUGGESTIONS))
    }

    private fun resetComposing() {
        misspelled = null
        composing.setLength(0)
        composingIsGesture = false
        onSuggestions(emptyList())
    }

    private fun casedForShift(words: List<String>): List<String> =
        words.take(SUGGESTIONS).map { TextRules.applyShift(it, shift.state, locale) }

    companion object {
        const val SUGGESTIONS = 3
        private const val MIN_CORRECTED = 2
        private const val MAX_WORD = 40

        /** A longer word is deleted in several steps. */
        private const val WORD_LOOKBACK = 64
        private const val CLEAR_LOOKAROUND = 100_000
        const val DOUBLE_SPACE_MILLIS = 500L
    }
}
