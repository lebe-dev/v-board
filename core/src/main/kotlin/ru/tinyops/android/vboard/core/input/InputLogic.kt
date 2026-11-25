package ru.tinyops.android.vboard.core.input

import ru.tinyops.android.vboard.core.dict.TapPoint
import ru.tinyops.android.vboard.core.dict.TapSuggestions
import java.util.Locale

/** Text immediately before and after the cursor. */
data class TextAround(
    val before: CharSequence,
    val after: CharSequence,
)

/** Optional fast path for editors that can fetch text on both sides of the cursor in one call. */
interface SurroundingTextProvider {
    fun textAroundCursor(
        beforeLength: Int,
        afterLength: Int,
    ): TextAround
}

internal fun TextEditor.textAroundCursor(
    beforeLength: Int,
    afterLength: Int,
): TextAround =
    if (this is SurroundingTextProvider) {
        textAroundCursor(beforeLength, afterLength)
    } else {
        TextAround(textBeforeCursor(beforeLength) ?: "", textAfterCursor(afterLength) ?: "")
    }

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
 *  - With nothing composed and the cursor after a word and a space, the words that usually follow
 *    it are offered; picking one inserts it with a space and offers the words after it.
 *
 * @param completions prefix completions (lowercase prefix and the text before the word -> words, most likely first)
 * @param onSuggestions receives the suggestions to display, primary first
 * @param corrections better spellings of a lowercase word the cursor stands on, most likely first; empty when the
 *   word is right or nothing is close
 * @param predictions the words to offer after the text before the cursor (lowercase, most likely first)
 */
class InputLogic(
    private val completions: (prefix: String, precedingText: String) -> List<String>,
    private val onSuggestions: (List<String>) -> Unit,
    private val corrections: (String) -> List<String> = { emptyList() },
    private val predictions: (precedingText: String) -> List<String> = { emptyList() },
    private val tapSuggestions: ((typed: String, taps: List<TapPoint?>, precedingText: String) -> TapSuggestions)? =
        null,
    private val onAutocorrectHighlight: (Boolean) -> Unit = {},
) {
    val shift = ShiftController()
    var locale: Locale = Locale.ENGLISH

    /** False for passwords and editors that opt out of suggestions: text is committed directly. */
    var suggestionsAllowed: Boolean = true

    /** Whether the words that may follow are offered after a space; completions do not depend on it. */
    var nextWordsAllowed: Boolean = true

    /** Whether confident typo corrections are applied automatically on space or punctuation. */
    var autocorrectEnabled: Boolean = true

    /** Whether accidental spaces right before punctuation are removed when typing `, . ! ? ; : …`. */
    var trimSpaceBeforePunctuation: Boolean = true

    private val composing = StringBuilder()
    private val composingTaps = ArrayList<TapPoint?>()
    private val autocorrect = AutocorrectState()
    private var composingIsGesture = false
    private var phantomSpace = false

    /** The text before the word being typed, for its completions. */
    private var beforeComposing = ""

    /** The word under the cursor that the shown corrections are for, null when none are shown. */
    private var misspelled: WordAtCursor? = null
    private val singleCorrection = SingleInputCorrection()

    private val strip = SuggestionStrip(onSuggestions, predictions)
    private var lastSpaceAt = Long.MIN_VALUE

    val composingText: String get() = composing.toString()

    val isComposing: Boolean get() = composing.isNotEmpty()

    val isComposingGesture: Boolean get() = composingIsGesture

    fun typeCharacter(
        editor: TextEditor,
        raw: String,
        tap: TapPoint? = null,
    ) {
        val isWordCharacter = TextRules.isWordCharacter(raw)
        val text = if (isWordCharacter) TextRules.applyShift(raw, shift.state, locale) else raw
        autocorrect.clearUndo()
        // One batch, so the editor reports a single, consistent selection update.
        editor.beginBatchEdit()
        if (composingIsGesture) commitComposing(editor)
        if (isWordCharacter) {
            if (phantomSpace && composing.isEmpty()) editor.commitText(" ")
            phantomSpace = false
            if (suggestionsAllowed) {
                if (composing.isEmpty() && !reopenWord(editor, dropLast = 0, extraLength = text.length)) {
                    beforeComposing = editor.textBeforeCursor(CONTEXT_CHARS)?.toString().orEmpty()
                }
                composing.append(text)
                repeat(text.length) { composingTaps.add(tap) }
                editor.setComposingText(composing)
                showCompletions(editor)
            } else {
                editor.commitText(text)
            }
            shift.onLetterTyped()
        } else {
            val hadComposing = composing.isNotEmpty()
            val corrected =
                hadComposing &&
                    AutocorrectState.triggersAutocorrect(text) &&
                    autocorrect.applyIfPending(editor, composing.toString(), text, autocorrectEnabled)
            if (corrected) {
                resetComposing()
            } else {
                commitComposing(editor)
                val canTrim = !hadComposing && suggestionsAllowed && trimSpaceBeforePunctuation
                commitPunctuation(editor, text, canTrim)
            }
            phantomSpace = false
            clearCorrections()
            strip.dropNextWords()
        }
        editor.endBatchEdit()
    }

    fun typeSpace(
        editor: TextEditor,
        nowMillis: Long,
    ) {
        autocorrect.clearUndo()
        editor.beginBatchEdit()
        val corrected =
            !composingIsGesture &&
                composing.isNotEmpty() &&
                autocorrect.applyIfPending(editor, composing.toString(), " ", autocorrectEnabled)
        if (corrected) {
            resetComposing()
            phantomSpace = false
            lastSpaceAt = nowMillis
        } else {
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
        }
        if (!showPredictions(editor) && !showSingleInputCorrection(editor)) strip.dropNextWords()
        editor.endBatchEdit()
    }

    fun typeEnter(editor: TextEditor) {
        autocorrect.clearUndo()
        commitComposing(editor)
        phantomSpace = false
        editor.sendEnter()
        clearCorrections()
        strip.dropNextWords()
    }

    fun backspace(editor: TextEditor) {
        phantomSpace = false
        when {
            composingIsGesture -> {
                autocorrect.clearUndo()
                editor.setComposingText("")
                resetComposing()
            }
            composing.isNotEmpty() -> {
                autocorrect.clearUndo()
                composing.setLength(composing.length - 1)
                if (composingTaps.isNotEmpty()) composingTaps.removeAt(composingTaps.lastIndex)
                editor.setComposingText(composing)
                showCompletions(editor)
            }
            autocorrect.undoIfMatching(editor) -> {
                clearCorrections()
                strip.dropNextWords()
            }
            suggestionsAllowed && reopenWord(editor, dropLast = 1, extraLength = 0) -> {
                editor.beginBatchEdit()
                editor.setComposingText(composing)
                showCompletions(editor)
                editor.endBatchEdit()
            }
            else -> {
                editor.sendBackspace()
                clearCorrections()
                strip.dropNextWords()
            }
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
        autocorrect.clearUndo()
        phantomSpace = false
        if (composing.isNotEmpty()) {
            editor.setComposingText("")
            resetComposing()
            return
        }
        val length = TextRules.wordBeforeLength(editor.textBeforeCursor(WORD_LOOKBACK))
        if (length > 0) editor.replaceAroundCursor(length, 0, "")
        clearCorrections()
        strip.dropNextWords()
    }

    /** Deletes the selection and up to [CLEAR_LOOKAROUND] characters on each side of it. */
    private fun deleteAll(editor: TextEditor) {
        autocorrect.clearUndo()
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
        autocorrect.clearUndo()
        when {
            misspelled != null && composing.isEmpty() -> {
                val target = misspelled ?: return
                misspelled = null
                editor.replaceAroundCursor(target.before, target.after, word)
                strip.show(emptyList())
            }
            !composingIsGesture && singleCorrection.apply(editor, word) { commitComposing(editor) } -> {
                if (!showPredictions(editor)) strip.show(emptyList())
            }
            strip.offersNextWords && composing.isEmpty() -> {
                editor.beginBatchEdit()
                editor.commitText("$word ")
                // The space came with the word: a space right after it is not a double space.
                lastSpaceAt = Long.MIN_VALUE
                shift.onLetterTyped()
                showPredictions(editor)
                editor.endBatchEdit()
            }
            composingIsGesture -> {
                // Swap the swiped word for an alternative; it stays replaceable.
                composing.setLength(0)
                composing.append(word)
                editor.setComposingText(word)
            }
            composing.isNotEmpty() -> {
                editor.beginBatchEdit()
                editor.commitText("$word ")
                resetComposing()
                showPredictions(editor)
                editor.endBatchEdit()
            }
        }
    }

    /** A gesture started: whatever is being composed becomes final. */
    fun startGesture(editor: TextEditor) {
        autocorrect.clearUndo()
        commitComposing(editor)
        clearCorrections()
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
        clearCorrections(dropStrip = !suggestionsAllowed)
        if (TextRules.needsSpaceBefore(editor.textBeforeCursor(1))) editor.commitText(" ")
        if (suggestionsAllowed) {
            composing.append(word)
            composingIsGesture = true
            editor.setComposingText(word)
            strip.show(candidates)
        } else {
            editor.commitText(word)
        }
        editor.endBatchEdit()
        phantomSpace = true
        shift.onLetterTyped()
    }

    /** Live suggestions for a gesture still in progress. */
    fun showGesturePreview(words: List<String>) {
        clearCorrections(dropStrip = false)
        strip.show(casedForShift(words))
    }

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
        val movedAway =
            if (candidatesEnd != -1) {
                selectionStart != candidatesEnd || selectionEnd != candidatesEnd
            } else {
                composing.isNotEmpty() &&
                    editor.textBeforeCursor(composing.length)?.toString() != composing.toString()
            }
        if (composing.isNotEmpty() && movedAway) {
            autocorrect.clearUndo()
            commitComposing(editor)
            phantomSpace = false
        }
        when {
            selectionStart != selectionEnd -> {
                autocorrect.clearUndo()
                if (misspelled != null || singleCorrection.target != null) {
                    misspelled = null
                    singleCorrection.clear()
                    strip.show(emptyList())
                }
                strip.dropNextWords()
            }
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
        if (word == null || better.isEmpty()) {
            val hadCorrections = misspelled != null || singleCorrection.target != null
            misspelled = null
            singleCorrection.clear()
            if (!showPredictions(editor) && !showSingleInputCorrection(editor) && hadCorrections) {
                strip.show(emptyList())
            }
            return
        }
        singleCorrection.clear()
        misspelled = word
        strip.show(better.map { TextRules.matchCase(it, word.text, locale) }.distinct().take(SUGGESTIONS))
    }

    /** Offers the words that may follow the text before the cursor, if nothing is composed; true when offered. */
    private fun showPredictions(editor: TextEditor): Boolean {
        if (suggestionsAllowed && nextWordsAllowed && composing.isEmpty()) {
            return strip.offerNextWords(editor, ::casedForShift)
        }
        strip.dropNextWords()
        return false
    }

    private fun showSingleInputCorrection(editor: TextEditor): Boolean {
        if (!suggestionsAllowed) return false
        val found = singleCorrection.findAndFormat(editor, locale, corrections, SUGGESTIONS) ?: return false
        strip.show(found)
        return true
    }

    /** Commits the composing text, if any. */
    fun commitComposing(editor: TextEditor) {
        if (composing.isEmpty()) return
        editor.finishComposingText()
        resetComposing()
    }

    /** The finger is about to move the cursor: what was typed is final and a next space starts afresh. */
    fun startCursorMove(editor: TextEditor) {
        autocorrect.clearUndo()
        commitComposing(editor)
        phantomSpace = false
        lastSpaceAt = Long.MIN_VALUE
        strip.dropNextWords()
    }

    /** Forgets all state, e.g. when a new editor gets focus. */
    fun reset() {
        autocorrect.reset()
        resetComposing()
        singleCorrection.invalidate()
        phantomSpace = false
        lastSpaceAt = Long.MIN_VALUE
    }

    /** Recomputes completions for the word being typed (e.g. once a dictionary is loaded). */
    fun refreshCompletions(editor: TextEditor? = null) {
        singleCorrection.invalidate()
        if (composing.isNotEmpty() && !composingIsGesture) showCompletions(editor)
    }

    private fun showCompletions(editor: TextEditor?) {
        if (composing.isEmpty() || !suggestionsAllowed) {
            autocorrect.clearPending()
            onAutocorrectHighlight(false)
            singleCorrection.clear()
            strip.show(emptyList())
            return
        }
        val typed = composing.toString()
        val lower = typed.lowercase(locale)
        val result =
            tapSuggestions?.invoke(lower, composingTaps, beforeComposing)
                ?: TapSuggestions(completions(lower, beforeComposing))
        val words = result.words.map { TextRules.matchCase(it, typed, locale) }
        val pending =
            result.autocorrect
                ?.takeIf { autocorrectEnabled }
                ?.let { TextRules.matchCase(it, typed, locale) }
                ?.takeIf { it != typed }
        autocorrect.setPending(pending)
        onAutocorrectHighlight(pending != null)
        val offered =
            if (pending == null && words.any { it == typed }) {
                words.filter { it != typed }.take(SUGGESTIONS)
            } else {
                val primary = pending ?: words.firstOrNull() ?: typed
                (listOf(primary, typed) + words).distinct().take(SUGGESTIONS)
            }
        if (offered.isNotEmpty()) {
            singleCorrection.clear()
            strip.show(offered)
        } else if (editor == null || !showSingleInputCorrection(editor)) {
            strip.show(emptyList())
        }
    }

    private fun reopenWord(
        editor: TextEditor,
        dropLast: Int,
        extraLength: Int,
    ): Boolean {
        val context = editor.textBeforeCursor(CONTEXT_CHARS + MAX_WORD)?.toString().orEmpty()
        val after = editor.textAfterCursor(1)?.firstOrNull()
        val word = trailingComposableWord(context, after) ?: return false
        val kept = word.dropLast(dropLast)
        if (kept.isEmpty() || word.length + extraLength >= MAX_WORD) return false
        beforeComposing = context.dropLast(word.length).takeLast(CONTEXT_CHARS)
        misspelled = null
        editor.replaceAroundCursor(word.length, 0, "")
        composing.append(kept)
        repeat(kept.length) { composingTaps.add(null) }
        return true
    }

    private fun clearCorrections(dropStrip: Boolean = true) {
        val hadCorrections = misspelled != null || singleCorrection.target != null
        misspelled = null
        singleCorrection.clear()
        if (dropStrip && hadCorrections) strip.show(emptyList())
    }

    private fun resetComposing() {
        autocorrect.clearPending()
        onAutocorrectHighlight(false)
        misspelled = null
        singleCorrection.clear()
        composing.setLength(0)
        composingTaps.clear()
        composingIsGesture = false
        strip.show(emptyList())
    }

    private fun casedForShift(words: List<String>): List<String> =
        words.take(SUGGESTIONS).map { TextRules.applyShift(it, shift.state, locale) }

    companion object {
        const val SUGGESTIONS = 3
        internal const val MAX_WORD = 40

        /** How much text before the cursor the next words and completions look at. */
        internal const val CONTEXT_CHARS = 64

        /** A longer word is deleted in several steps. */
        private const val WORD_LOOKBACK = 64
        internal const val PUNCTUATION_LOOKBACK = 16
        private const val CLEAR_LOOKAROUND = 100_000
        const val DOUBLE_SPACE_MILLIS = 500L
    }
}

private fun commitPunctuation(
    editor: TextEditor,
    text: String,
    trimSpaces: Boolean,
) {
    val spaces =
        if (trimSpaces && TextRules.isAttachedPunctuation(text)) {
            TextRules.spacesBeforePunctuation(editor.textBeforeCursor(InputLogic.PUNCTUATION_LOOKBACK), text)
        } else {
            0
        }
    if (spaces > 0) editor.replaceAroundCursor(spaces, 0, text) else editor.commitText(text)
}

private const val MIN_CORRECTED = 2

private class WordAtCursor(
    val text: String,
    val before: Int,
    val after: Int,
)

private fun wordAtCursor(editor: TextEditor): WordAtCursor? {
    val around = editor.textAroundCursor(InputLogic.MAX_WORD, InputLogic.MAX_WORD)
    val before = around.before.takeLastWhile(TextRules::isInWord).toString()
    val after = around.after.takeWhile(TextRules::isInWord).toString()
    val text = before + after
    if (text.length < MIN_CORRECTED || text.length >= InputLogic.MAX_WORD) return null
    if (text.first() == '-' || text.last() == '-') return null
    return WordAtCursor(text, before.length, after.length)
}

private fun trailingComposableWord(
    context: String,
    after: Char?,
): String? {
    if (after != null && TextRules.isInWord(after)) return null
    val word = context.takeLastWhile(TextRules::isComposableChar)
    if (word.isEmpty() || !word.first().isLetter()) return null
    val prev = context.getOrNull(context.length - word.length - 1)
    if (prev != null && (prev.isDigit() || prev == '_')) return null
    return word
}
