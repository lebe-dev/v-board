package ru.tinyops.android.vboard.core.dict

import kotlin.math.ln

/**
 * Word suggestions that take the previous word into account, from a [BigramTable] and the
 * [dictionary] of the same language. Previous words are folded, as [BigramTable.previousWord] gives them.
 *
 * Not thread-safe: meant for the main thread, where suggestions are shown.
 */
class NextWords(
    private val table: BigramTable,
    private val dictionary: Dictionary,
) {
    /** The last answers of [after]: the same previous word comes up again and again. */
    private val recent =
        object : LinkedHashMap<String, List<String>>(RECENT, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > RECENT
        }

    /** Up to [limit] (at most [MAX_LIMIT]) words most often seen after [previous], most frequent first. */
    fun after(
        previous: String,
        limit: Int,
    ): List<String> {
        val words =
            recent.getOrPut(previous) {
                table
                    .context(previous)
                    ?.mostFrequent(MAX_LIMIT)
                    ?.mapNotNull(::spelling)
                    .orEmpty()
            }
        return words.take(limit)
    }

    /**
     * Up to [limit] dictionary words starting with [prefix] (lowercase), the most likely after [previous] first:
     * the word frequency weighed with how much more often the word follows [previous] than it is used at all.
     */
    fun completions(
        previous: String,
        prefix: String,
        limit: Int,
    ): List<String> {
        val frequent = dictionary.completions(prefix, limit)
        val context = table.context(previous) ?: return frequent
        val followers = context.mostFrequent(limit, fold(prefix)).mapNotNull(::spelling)
        return (frequent + followers)
            .distinct()
            .sortedByDescending { word -> ln(dictionary.frequency(word) ?: 0.0) + context.association(fold(word)) }
            .take(limit)
    }

    /** The dictionary spelling of a folded [word]: `ё` comes back where the dictionary has it. */
    private fun spelling(word: String): String? {
        if (dictionary.contains(word)) return word
        for (i in word.indices) {
            if (word[i] != 'е') continue
            val restored = word.substring(0, i) + 'ё' + word.substring(i + 1)
            if (dictionary.contains(restored)) return restored
        }
        return null
    }

    private fun fold(word: String): String = word.replace('ё', 'е')

    companion object {
        /** The most words [after] gives. */
        const val MAX_LIMIT = 8
        private const val RECENT = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
