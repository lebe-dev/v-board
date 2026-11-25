package ru.tinyops.android.vboard.core.dict

import kotlin.math.ln

/**
 * Suggestions and typo corrections for manual tap typing. Combines dictionary frequencies,
 * optional bigram context, keyboard proximity, touch coordinates, fuzzy prefix completions,
 * `е`/`ё` restoration and two-edit corrections for longer words.
 */
class TapSuggester(
    private val dictionary: Dictionary,
    private val proximity: KeyProximity,
    private val bigrams: BigramTable? = null,
    private val nextWords: NextWords? = null,
) {
    private data class Candidate(
        val word: String,
        val editCost: Double,
        val edits: Int,
        val extraChars: Int,
        val score: Double,
    )

    /**
     * Suggests up to [limit] lowercase words for [typed] (lowercase) with optional [taps]
     * (same length as [typed], normalized key units) and [precedingText] before the word.
     */
    fun suggest(
        typed: String,
        taps: List<TapPoint?> = emptyList(),
        precedingText: String = "",
        limit: Int = DEFAULT_LIMIT,
        options: TapSuggestOptions = TapSuggestOptions(),
    ): TapSuggestions {
        if (typed.isEmpty() || limit <= 0) return TapSuggestions(emptyList())
        val context = previousContext(precedingText)
        val known = dictionary.contains(typed)
        val pool = LinkedHashMap<String, Candidate>()

        addExactCompletions(typed, precedingText, limit, context, pool)
        if (options.corrections || options.autocorrect) {
            collectCorrections(typed, taps, known, options, context, pool)
        }

        val ranked = pool.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        val autocorrect =
            if (options.autocorrect && !known && typed.length >= MIN_CORRECTION_LEN) {
                pickAutocorrect(typed, ranked)
            } else {
                null
            }
        val shown = if (options.corrections) ranked else ranked.filter { it.edits == 0 }
        return TapSuggestions(buildWordList(shown, autocorrect, limit), autocorrect)
    }

    private fun collectCorrections(
        typed: String,
        taps: List<TapPoint?>,
        known: Boolean,
        options: TapSuggestOptions,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        addYoVariants(typed, context, pool)
        if (!known) {
            if (typed.length >= MIN_CORRECTION_LEN) {
                addOneEditCorrections(typed, taps, options.spatialModel, context, pool)
            }
            if (options.fuzzyPrefix && typed.length >= MIN_FUZZY_PREFIX_LEN) {
                addFuzzyPrefixCompletions(typed, taps, options.spatialModel, context, pool)
            }
            if (options.twoEdits && typed.length >= MIN_TWO_EDIT_LEN) {
                addTwoEditCorrections(typed, taps, options.spatialModel, context, pool)
            }
        }
    }

    private fun buildWordList(
        ranked: List<Candidate>,
        autocorrect: String?,
        limit: Int,
    ): List<String> =
        buildList {
            if (autocorrect != null) add(autocorrect)
            for (candidate in ranked) {
                if (candidate.word !in this) add(candidate.word)
                if (size >= limit) break
            }
        }

    /** Corrections for a committed misspelled [word] (lowercase) under the cursor. */
    fun corrections(
        word: String,
        precedingText: String = "",
        limit: Int = DEFAULT_LIMIT,
        options: TapSuggestOptions = TapSuggestOptions(),
    ): List<String> {
        if (word.length < MIN_CORRECTION_LEN || limit <= 0) return emptyList()
        val ownFreq = dictionary.frequency(word)
        if (ownFreq != null && ownFreq >= RARE_WORD_MAX_FREQ) return emptyList()
        val context = previousContext(precedingText)
        val pool = LinkedHashMap<String, Candidate>()
        addYoVariants(word, context, pool)
        addOneEditCorrections(word, emptyList(), spatialModel = false, context, pool)
        if (ownFreq == null && options.twoEdits && word.length >= MIN_TWO_EDIT_LEN) {
            addTwoEditCorrections(word, emptyList(), spatialModel = false, context, pool)
        }
        val ownScore = ownFreq?.let { wordScore(word, it, context) }
        return pool.values
            .asSequence()
            .filter { ownScore == null || it.score > ownScore }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
            .take(limit)
            .map { it.word }
            .toList()
    }

    private fun previousContext(precedingText: String): BigramTable.Context? {
        val table = bigrams ?: return null
        val previous = BigramTable.previousWord(precedingText) ?: return null
        return table.context(previous)
    }

    private fun addExactCompletions(
        typed: String,
        precedingText: String,
        limit: Int,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val previous = if (nextWords != null) BigramTable.previousWord(precedingText) else null
        val completions =
            if (previous != null && nextWords != null) {
                nextWords.completions(previous, typed, limit + EXTRA_COMPLETIONS)
            } else {
                dictionary.completions(typed, limit + EXTRA_COMPLETIONS)
            }
        for (word in completions) {
            val extra = (word.length - typed.length).coerceAtLeast(0)
            offer(word, editCost = 0.0, edits = 0, extraChars = extra, context = context, pool = pool)
        }
    }

    /** Restores `ё` in Russian words where `е` was typed instead. */
    private fun addYoVariants(
        typed: String,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        if ('е' !in typed) return
        val sb = StringBuilder(typed)
        for (i in typed.indices) {
            if (typed[i] != 'е') continue
            sb.setCharAt(i, 'ё')
            val index = dictionary.indexOf(sb)
            if (index >= 0) {
                offerAt(sb.toString(), index, TapEditDistance.YO_COST, edits = 1, extraChars = 0, context, pool)
            }
            sb.setCharAt(i, 'е')
        }
    }

    private class OneEditSink(
        val context: BigramTable.Context?,
        val pool: MutableMap<String, Candidate>,
        val sb: StringBuilder,
    )

    private fun addOneEditCorrections(
        typed: String,
        taps: List<TapPoint?>,
        spatialModel: Boolean,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val alphabet = alphabetOf(typed)
        val sink = OneEditSink(context, pool, StringBuilder(typed.length + 1))
        val sb = sink.sb
        for (i in typed.indices) {
            if (typed.length > 1) {
                sb.setLength(0)
                sb.append(typed, 0, i).append(typed, i + 1, typed.length)
                val delIdx = dictionary.indexOf(sb)
                if (delIdx >= 0) {
                    val cost = TapEditDistance.deleteCost(typed, i, proximity)
                    offerAt(sb.toString(), delIdx, cost, edits = 1, 0, context, pool)
                }
            }
            if (i + 1 < typed.length && typed[i] != typed[i + 1]) {
                sb.setLength(0)
                sb.append(typed)
                sb.setCharAt(i, typed[i + 1])
                sb.setCharAt(i + 1, typed[i])
                val swapIdx = dictionary.indexOf(sb)
                if (swapIdx >= 0) {
                    offerAt(sb.toString(), swapIdx, TapEditDistance.TRANSPOSE_COST, edits = 1, 0, context, pool)
                }
            }
            addLetterEditsAt(typed, i, alphabet, taps.getOrNull(i), spatialModel, sink)
        }
        sb.setLength(0)
        sb.append(typed).append(' ')
        for (letter in alphabet) {
            sb.setCharAt(typed.length, letter)
            val insIdx = dictionary.indexOf(sb)
            if (insIdx >= 0) {
                offerAt(sb.toString(), insIdx, TapEditDistance.INSERT_COST, edits = 1, 0, context, pool)
            }
        }
    }

    private fun addLetterEditsAt(
        typed: String,
        index: Int,
        alphabet: String,
        tap: TapPoint?,
        spatialModel: Boolean,
        sink: OneEditSink,
    ) {
        val sb = sink.sb
        val original = typed[index]
        sb.setLength(0)
        sb.append(typed)
        for (letter in alphabet) {
            if (letter == original) continue
            sb.setCharAt(index, letter)
            val dictIndex = dictionary.indexOf(sb)
            if (dictIndex >= 0) {
                val cost = TapEditDistance.substitutionCost(original, letter, tap, proximity, spatialModel)
                offerAt(sb.toString(), dictIndex, cost, edits = 1, 0, sink.context, sink.pool)
            }
        }
        sb.setLength(0)
        sb.append(typed, 0, index).append(' ').append(typed, index, typed.length)
        for (letter in alphabet) {
            sb.setCharAt(index, letter)
            val dictIndex = dictionary.indexOf(sb)
            if (dictIndex >= 0) {
                offerAt(sb.toString(), dictIndex, TapEditDistance.INSERT_COST, edits = 1, 0, sink.context, sink.pool)
            }
        }
    }

    private fun addFuzzyPrefixCompletions(
        typed: String,
        taps: List<TapPoint?>,
        spatialModel: Boolean,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        for (i in typed.indices) {
            val ch = typed[i]
            val tap = taps.getOrNull(i)
            val near = if (spatialModel && tap != null) proximity.nearKeys(tap) else proximity.nearKeys(ch)
            for (letter in near) {
                if (letter == ch) continue
                val mutated = typed.substring(0, i) + letter + typed.substring(i + 1)
                val cost = TapEditDistance.substitutionCost(ch, letter, tap, proximity, spatialModel)
                collectFuzzyCompletions(mutated, typed.length, cost, context, pool)
            }
            if (ch == 'е') {
                val mutated = typed.substring(0, i) + 'ё' + typed.substring(i + 1)
                collectFuzzyCompletions(mutated, typed.length, TapEditDistance.YO_COST, context, pool)
            }
            if (i + 1 < typed.length && ch != typed[i + 1]) {
                val swapped = typed.substring(0, i) + typed[i + 1] + ch + typed.substring(i + 2)
                collectFuzzyCompletions(swapped, typed.length, TapEditDistance.TRANSPOSE_COST, context, pool)
            }
            if (typed.length >= MIN_FUZZY_DELETE_LEN) {
                val deleted = typed.removeRange(i, i + 1)
                collectFuzzyCompletions(
                    deleted,
                    typed.length,
                    TapEditDistance.deleteCost(typed, i, proximity),
                    context,
                    pool,
                )
            }
        }
    }

    private fun collectFuzzyCompletions(
        prefix: String,
        typedLength: Int,
        editCost: Double,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        for (word in dictionary.completions(prefix, FUZZY_PREFIX_PER_VARIANT)) {
            val extra = (word.length - typedLength).coerceAtLeast(0)
            if (extra <= 0) continue
            offer(word, editCost, edits = 1, extraChars = extra, context = context, pool = pool)
        }
    }

    private fun addTwoEditCorrections(
        typed: String,
        taps: List<TapPoint?>,
        spatialModel: Boolean,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val buckets = LinkedHashSet<Char>()
        buckets += typed[0]
        if (typed[0] == 'е') buckets += 'ё'
        buckets += typed[1]
        val firstTap = taps.getOrNull(0)
        val nearFirst =
            if (spatialModel && firstTap != null) {
                proximity.nearKeys(firstTap)
            } else {
                proximity.nearKeys(typed[0])
            }
        for (letter in nearFirst.take(MAX_FIRST_NEIGHBORS)) buckets += letter

        val scratch = TapEditDistance.TwoEditScratch(typed.length)
        for (firstChar in buckets) {
            scanTwoEditBucket(typed, firstChar, taps, spatialModel, scratch, context, pool)
        }
    }

    private fun scanTwoEditBucket(
        typed: String,
        firstChar: Char,
        taps: List<TapPoint?>,
        spatialModel: Boolean,
        scratch: TapEditDistance.TwoEditScratch,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val table = dictionary.words
        for (index in dictionary.prefixRange(firstChar)) {
            val dist = TapEditDistance.withinTwo(typed, table, index, scratch = scratch)
            if (dist > 2) continue
            val word = table.wordAt(index)
            if (word !in pool) {
                val cost = TapEditDistance.weightedCost(typed, word, taps, proximity, spatialModel)
                if (cost <= MAX_TWO_EDIT_COST) {
                    offerAt(word, index, cost, edits = dist, extraChars = 0, context = context, pool = pool)
                }
            }
        }
    }

    private fun offerAt(
        word: String,
        dictIndex: Int,
        editCost: Double,
        edits: Int,
        extraChars: Int,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        offerWithFrequency(
            word = word,
            frequency = dictionary.frequencies[dictIndex],
            editCost = editCost,
            edits = edits,
            extraChars = extraChars,
            context = context,
            pool = pool,
        )
    }

    private fun offer(
        word: String,
        editCost: Double,
        edits: Int,
        extraChars: Int,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val frequency = dictionary.frequency(word) ?: return
        offerWithFrequency(word, frequency, editCost, edits, extraChars, context, pool)
    }

    private fun offerWithFrequency(
        word: String,
        frequency: Double,
        editCost: Double,
        edits: Int,
        extraChars: Int,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val extensionPenalty =
            if (edits == 0) {
                EXACT_EXTENSION_PENALTY * extraChars
            } else {
                FUZZY_EXTENSION_BASE + FUZZY_EXTENSION_PER_CHAR * extraChars
            }
        val score = wordScore(word, frequency, context) - EDIT_WEIGHT * editCost - extensionPenalty
        val existing = pool[word]
        if (existing == null || score > existing.score) {
            pool[word] = Candidate(word, editCost, edits, extraChars, score)
        }
    }

    private fun wordScore(
        word: String,
        frequency: Double,
        context: BigramTable.Context?,
    ): Double {
        val bigram = context?.association(word.replace('ё', 'е')) ?: 0.0
        return ln(frequency.coerceAtLeast(MIN_FREQ)) + BIGRAM_WEIGHT * bigram
    }

    private fun pickAutocorrect(
        typed: String,
        ranked: List<Candidate>,
    ): String? {
        val best = ranked.firstOrNull { it.edits > 0 && it.extraChars == 0 } ?: return null
        val maxCost =
            when {
                best.edits <= 1 -> MAX_ONE_EDIT_AUTOCORRECT_COST
                typed.length >= MIN_TWO_EDIT_LEN -> MAX_TWO_EDIT_AUTOCORRECT_COST
                else -> return null
            }
        return best.word.takeIf { best.editCost <= maxCost }
    }

    private fun alphabetOf(word: String): String = if (word.any { it in CYRILLIC }) RUSSIAN_LETTERS else LATIN_LETTERS

    companion object {
        private const val DEFAULT_LIMIT = 3
        private const val EXTRA_COMPLETIONS = 2
        private const val MIN_CORRECTION_LEN = 2
        private const val MIN_FUZZY_PREFIX_LEN = 3
        private const val MIN_FUZZY_DELETE_LEN = 4
        private const val MIN_TWO_EDIT_LEN = 5
        private const val FUZZY_PREFIX_PER_VARIANT = 2
        private const val MAX_FIRST_NEIGHBORS = 4
        private const val MIN_FREQ = 1e-12
        private const val RARE_WORD_MAX_FREQ = 1e-6
        private const val BIGRAM_WEIGHT = 1.0
        private const val EDIT_WEIGHT = 4.5
        private const val EXACT_EXTENSION_PENALTY = 0.02
        private const val FUZZY_EXTENSION_BASE = 2.5
        private const val FUZZY_EXTENSION_PER_CHAR = 0.35
        private const val MAX_ONE_EDIT_AUTOCORRECT_COST = 1.15
        private const val MAX_TWO_EDIT_AUTOCORRECT_COST = 1.75
        private const val MAX_TWO_EDIT_COST = 2.35
        private const val RUSSIAN_LETTERS = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        private const val LATIN_LETTERS = "abcdefghijklmnopqrstuvwxyz"
        private val CYRILLIC = 'а'..'я'
    }
}
