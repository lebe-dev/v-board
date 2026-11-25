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

    private class CachedScan(
        val rawBefore: String,
        val rawAfter: String,
        val locale: Locale,
        val suggestions: List<String>?,
        val target: Target?,
    )

    var target: Target? = null
        private set

    private var cachedLocale: Locale? = null
    private var lastScan: CachedScan? = null
    private val wordCache =
        object : LinkedHashMap<String, List<String>>(CACHE_INITIAL_CAPACITY, CACHE_LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
                size > MAX_WORD_CACHE
        }

    fun clear() {
        target = null
    }

    fun invalidate() {
        target = null
        lastScan = null
        wordCache.clear()
    }

    fun findAndFormat(
        editor: TextEditor,
        locale: Locale,
        corrections: (String) -> List<String>,
        limit: Int,
    ): List<String>? {
        if (cachedLocale != locale) {
            wordCache.clear()
            lastScan = null
            cachedLocale = locale
        }
        val around = editor.textAroundCursor(SCAN_CHARS, SCAN_CHARS)
        val rawBefore = around.before.toString()
        val rawAfter = around.after.toString()
        lastScan?.let { cached ->
            if (cached.rawBefore == rawBefore && cached.rawAfter == rawAfter && cached.locale == locale) {
                target = cached.target
                return cached.suggestions
            }
        }
        val full = rawBefore + rawAfter
        val cursor = rawBefore.length
        var found: FoundWord? = null
        forEachWordSpan(full, rawBefore.length == SCAN_CHARS, rawAfter.length == SCAN_CHARS) { start, end ->
            val word = full.substring(start, end)
            val lower = word.lowercase(locale)
            val fixes = wordCache.getOrPut(lower) { corrections(lower) }
            if (fixes.isNotEmpty()) {
                if (found != null) {
                    return recordAndReturn(rawBefore, rawAfter, locale, null, null)
                }
                found = FoundWord(start, end, word, fixes)
            }
        }
        val single = found ?: return recordAndReturn(rawBefore, rawAfter, locale, null, null)
        val newTarget =
            buildTarget(single, full, cursor)
                ?: return recordAndReturn(rawBefore, rawAfter, locale, null, null)
        val suggestions =
            single.fixes
                .map { TextRules.matchCase(it, single.text, locale) }
                .distinct()
                .take(limit)
        return recordAndReturn(rawBefore, rawAfter, locale, suggestions, newTarget)
    }

    fun apply(
        editor: TextEditor,
        replacement: String,
        commitComposing: () -> Unit,
    ): Boolean {
        val current = target ?: return false
        target = null
        lastScan = null
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

    private fun recordAndReturn(
        rawBefore: String,
        rawAfter: String,
        locale: Locale,
        suggestions: List<String>?,
        newTarget: Target?,
    ): List<String>? {
        target = newTarget
        lastScan = CachedScan(rawBefore, rawAfter, locale, suggestions, newTarget)
        return suggestions
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
        const val MAX_WORD_CACHE = 256
        const val CACHE_INITIAL_CAPACITY = 64
        const val CACHE_LOAD_FACTOR = 0.75f

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
                emitValidSpan(text, rawStart, i, skipFirst, skipLast, onWord)
            }
        }

        inline fun emitValidSpan(
            text: String,
            rawStart: Int,
            rawEnd: Int,
            skipFirst: Boolean,
            skipLast: Boolean,
            onWord: (start: Int, end: Int) -> Unit,
        ) {
            if (isBoundaryCut(rawStart, rawEnd, text.length, skipFirst, skipLast)) return
            var start = rawStart
            var end = rawEnd
            while (start < end && text[start] == '-') start++
            while (end > start && text[end - 1] == '-') end--
            if (isValidSpan(text, start, end)) onWord(start, end)
        }

        fun isBoundaryCut(
            rawStart: Int,
            rawEnd: Int,
            textLength: Int,
            skipFirst: Boolean,
            skipLast: Boolean,
        ): Boolean = (skipFirst && rawStart == 0) || (skipLast && rawEnd == textLength)

        fun isValidSpan(
            text: String,
            start: Int,
            end: Int,
        ): Boolean =
            end - start in MIN_WORD until MAX_WORD &&
                !isWordJoinBoundary(text.getOrNull(start - 1)) &&
                !isWordJoinBoundary(text.getOrNull(end))

        fun isWordJoinBoundary(c: Char?): Boolean = c != null && (c.isDigit() || c == '_')
    }
}
