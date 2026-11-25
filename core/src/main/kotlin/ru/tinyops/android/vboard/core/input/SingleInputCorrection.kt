package ru.tinyops.android.vboard.core.input

import java.util.Locale

/**
 * Finds and replaces a lone fixable misspelling anywhere in the current input when the cursor is
 * elsewhere and the strip has no higher-priority suggestions.
 */
internal class SingleInputCorrection {
    data class Target(
        val text: String,
        val beforeCursor: Int,
        val afterCursor: Int,
        val head: String,
        val tail: String,
    )

    var target: Target? = null
        private set

    fun clear() {
        target = null
    }

    fun findAndFormat(
        editor: TextEditor,
        locale: Locale,
        corrections: (String) -> List<String>,
        limit: Int,
    ): List<String>? {
        val rawBefore = editor.textBeforeCursor(SCAN_CHARS)?.toString().orEmpty()
        val rawAfter = editor.textAfterCursor(SCAN_CHARS)?.toString().orEmpty()
        val full = rawBefore + rawAfter
        val cursor = rawBefore.length
        var found: FoundWord? = null
        forEachWordSpan(full, rawBefore.length == SCAN_CHARS, rawAfter.length == SCAN_CHARS) { start, end ->
            val word = full.substring(start, end)
            val fixes = corrections(word.lowercase(locale))
            if (fixes.isNotEmpty()) {
                if (found != null) {
                    target = null
                    return null
                }
                found = FoundWord(start, end, word, fixes)
            }
        }
        val single = found ?: return clearAndNull()
        target = buildTarget(single, full, cursor) ?: return clearAndNull()
        return single.fixes
            .map { TextRules.matchCase(it, single.text, locale) }
            .distinct()
            .take(limit)
    }

    fun apply(
        editor: TextEditor,
        replacement: String,
        commitComposing: () -> Unit,
    ): Boolean {
        val current = target ?: return false
        target = null
        editor.beginBatchEdit()
        commitComposing()
        editor.replaceAroundCursor(
            current.beforeCursor,
            current.afterCursor,
            current.head + replacement + current.tail,
        )
        editor.endBatchEdit()
        return true
    }

    private fun clearAndNull(): List<String>? {
        target = null
        return null
    }

    private fun buildTarget(
        word: FoundWord,
        full: String,
        cursor: Int,
    ): Target? =
        when {
            word.end <= cursor ->
                Target(word.text, cursor - word.start, 0, "", full.substring(word.end, cursor))
            word.start >= cursor ->
                Target(word.text, 0, word.end - cursor, full.substring(cursor, word.start), "")
            else -> null
        }

    private class FoundWord(
        val start: Int,
        val end: Int,
        val text: String,
        val fixes: List<String>,
    )

    private companion object {
        const val SCAN_CHARS = 512
        const val MIN_WORD = 2
        const val MAX_WORD = 40

        inline fun forEachWordSpan(
            text: String,
            skipFirst: Boolean,
            skipLast: Boolean,
            onWord: (start: Int, end: Int) -> Unit,
        ) {
            var i = 0
            while (i < text.length) {
                while (i < text.length && !TextRules.isInWord(text[i])) i++
                val rawStart = i
                while (i < text.length && TextRules.isInWord(text[i])) i++
                trimAndValidateSpan(text, rawStart, i, skipFirst, skipLast)?.let { (start, end) ->
                    onWord(start, end)
                }
            }
        }

        fun trimAndValidateSpan(
            text: String,
            rawStart: Int,
            rawEnd: Int,
            skipFirst: Boolean,
            skipLast: Boolean,
        ): Pair<Int, Int>? {
            val boundaryCut = (skipFirst && rawStart == 0) || (skipLast && rawEnd == text.length)
            if (boundaryCut) return null
            var start = rawStart
            var end = rawEnd
            while (start < end && text[start] == '-') start++
            while (end > start && text[end - 1] == '-') end--
            if (end - start !in MIN_WORD until MAX_WORD) return null
            if (isWordJoinBoundary(text.getOrNull(start - 1)) || isWordJoinBoundary(text.getOrNull(end))) return null
            return start to end
        }

        fun isWordJoinBoundary(c: Char?): Boolean = c != null && (c.isDigit() || c == '_')
    }
}
