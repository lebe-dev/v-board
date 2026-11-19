package ru.tinyops.android.vboard.core.input

/**
 * Hands suggestions to the strip and remembers whether they are the words that may follow the text
 * before the cursor: picking one of those inserts it instead of replacing a word.
 */
internal class SuggestionStrip(
    private val onSuggestions: (List<String>) -> Unit,
    private val predictions: (precedingText: String) -> List<String>,
) {
    var offersNextWords = false
        private set

    fun show(words: List<String>) {
        offersNextWords = false
        onSuggestions(words)
    }

    /**
     * Offers the words that may follow when the cursor stands after a space, not right before a word, cased
     * by [case]; otherwise takes offered ones away. Returns whether any are offered.
     */
    fun offerNextWords(
        editor: TextEditor,
        case: (List<String>) -> List<String>,
    ): Boolean {
        val words = case(wordsAfter(editor)).distinct()
        if (words.isEmpty()) {
            dropNextWords()
            return false
        }
        onSuggestions(words)
        offersNextWords = true
        return true
    }

    fun dropNextWords() {
        if (offersNextWords) show(emptyList())
    }

    private fun wordsAfter(editor: TextEditor): List<String> {
        val before = editor.textBeforeCursor(InputLogic.CONTEXT_CHARS)?.toString()
        if (before == null || !before.endsWith(' ')) return emptyList()
        val next = editor.textAfterCursor(1)?.firstOrNull()
        if (next != null && TextRules.isInWord(next)) return emptyList()
        return predictions(before)
    }
}
