package ru.tinyops.android.vboard.core.input

import java.util.Locale

/** Pure text rules used by the input logic. */
object TextRules {
    private const val NO_SPACE_AFTER = "([{\"'«„“/-\n\t "

    /**
     * Whether a word inserted after [textBefore] needs a separating space (the "phantom space"
     * inserted before a gesture word).
     */
    fun needsSpaceBefore(textBefore: CharSequence?): Boolean {
        if (textBefore.isNullOrEmpty()) return false
        val last = textBefore.last()
        return !last.isWhitespace() && last !in NO_SPACE_AFTER
    }

    /**
     * Whether a second space typed right after a space should turn into ". ": the text ends with
     * "word " (a letter or digit followed by exactly one space).
     */
    fun isDoubleSpacePeriod(textBefore: CharSequence?): Boolean {
        if (textBefore == null || textBefore.length < 2) return false
        val beforeSpace = textBefore[textBefore.length - 2]
        return textBefore.last() == ' ' && beforeSpace.isLetterOrDigit()
    }

    /**
     * How many characters before the cursor make up the last word together with the spaces after it.
     * A run of punctuation counts as a word, and a line break goes alone.
     */
    fun wordBeforeLength(textBefore: CharSequence?): Int {
        if (textBefore.isNullOrEmpty()) return 0
        val end = textBefore.length
        if (textBefore[end - 1] == '\n') return 1
        var i = end
        while (i > 0 && textBefore[i - 1].isWhitespace() && textBefore[i - 1] != '\n') i--
        if (i == 0 || textBefore[i - 1] == '\n') return end - i
        if (textBefore[i - 1].isWordPart()) {
            while (i > 0 && (textBefore[i - 1].isWordPart() || isJoiner(textBefore, i - 1))) i--
        } else {
            while (i > 0 && !textBefore[i - 1].isWhitespace() && !textBefore[i - 1].isWordPart()) i--
        }
        return end - i
    }

    private fun Char.isWordPart(): Boolean = isLetterOrDigit() || this == '_' || isCombiningMark()

    /** A hyphen or an apostrophe inside a word, as in "кто-то" or "don't". */
    private fun isJoiner(
        text: CharSequence,
        index: Int,
    ): Boolean = text[index] in WORD_JOINERS && index > 0 && text[index - 1].isWordPart()

    private const val WORD_JOINERS = "-'’"

    /** Applies the shift state to a lowercase word. */
    fun applyShift(
        word: String,
        shift: ShiftState,
        locale: Locale,
    ): String =
        when (shift) {
            ShiftState.OFF -> word
            ShiftState.ONCE -> word.replaceFirstChar { it.titlecase(locale) }
            ShiftState.LOCKED -> word.uppercase(locale)
        }

    /** Characters that are part of a word being composed. */
    fun isWordCharacter(text: String): Boolean =
        text.isNotEmpty() && text[0].isLetter() && text.all { it.isLetter() || it.isCombiningMark() }

    private fun Char.isCombiningMark(): Boolean = Character.getType(this) == Character.NON_SPACING_MARK.toInt()

    /** Restores the user's capitalization of [typed] on a suggested [word]. */
    fun matchCase(
        word: String,
        typed: String,
        locale: Locale,
    ): String =
        when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(locale)
            typed.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.titlecase(locale) }
            else -> word
        }
}
