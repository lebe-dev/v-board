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
        if (options.corrections) {
            collectCorrections(typed, taps, known, options, context, pool)
        }

        val ranked = pool.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        val autocorrect =
            if (options.corrections && !known && typed.length >= MIN_CORRECTION_LEN) {
                pickAutocorrect(typed, ranked)
            } else {
                null
            }
        return TapSuggestions(buildWordList(ranked, autocorrect, limit), autocorrect)
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
        if (typed.length >= MIN_CORRECTION_LEN) {
            addOneEditCorrections(typed, taps, options.spatialModel, context, pool)
        }
        if (options.fuzzyPrefix && typed.length >= MIN_FUZZY_PREFIX_LEN) {
            addFuzzyPrefixCompletions(typed, taps, options.spatialModel, context, pool)
        }
        if (options.twoEdits && !known && typed.length >= MIN_TWO_EDIT_LEN) {
            addTwoEditCorrections(typed, taps, options.spatialModel, context, pool)
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
        if (word.length < MIN_CORRECTION_LEN || limit <= 0 || dictionary.contains(word)) return emptyList()
        val context = previousContext(precedingText)
        val pool = LinkedHashMap<String, Candidate>()
        addYoVariants(word, context, pool)
        addOneEditCorrections(word, emptyList(), spatialModel = false, context, pool)
        if (options.twoEdits && word.length >= MIN_TWO_EDIT_LEN) {
            addTwoEditCorrections(word, emptyList(), spatialModel = false, context, pool)
        }
        return pool.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
            .take(limit)
            .map { it.word }
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
        for (i in typed.indices) {
            if (typed[i] != 'е') continue
            val candidate = typed.substring(0, i) + 'ё' + typed.substring(i + 1)
            if (dictionary.contains(candidate)) {
                offer(candidate, TapEditDistance.YO_COST, edits = 1, extraChars = 0, context, pool)
            }
        }
    }

    private fun addOneEditCorrections(
        typed: String,
        taps: List<TapPoint?>,
        spatialModel: Boolean,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val alphabet = alphabetOf(typed)
        for (i in typed.indices) {
            val deleted = typed.removeRange(i, i + 1)
            if (deleted.isNotEmpty() && dictionary.contains(deleted)) {
                offer(deleted, TapEditDistance.deleteCost(typed, i, proximity), edits = 1, 0, context, pool)
            }
            if (i + 1 < typed.length && typed[i] != typed[i + 1]) {
                val swapped = typed.substring(0, i) + typed[i + 1] + typed[i] + typed.substring(i + 2)
                if (dictionary.contains(swapped)) {
                    offer(swapped, TapEditDistance.TRANSPOSE_COST, edits = 1, 0, context, pool)
                }
            }
            addLetterEditsAt(typed, i, alphabet, taps.getOrNull(i), spatialModel, context, pool)
        }
        for (letter in alphabet) {
            val appended = typed + letter
            if (dictionary.contains(appended)) {
                offer(appended, TapEditDistance.INSERT_COST, edits = 1, 0, context, pool)
            }
        }
    }

    private fun addLetterEditsAt(
        typed: String,
        index: Int,
        alphabet: String,
        tap: TapPoint?,
        spatialModel: Boolean,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val prefix = typed.substring(0, index)
        val suffixFrom = typed.substring(index)
        val suffixAfter = typed.substring(index + 1)
        val original = typed[index]
        for (letter in alphabet) {
            if (letter != original) {
                val replaced = prefix + letter + suffixAfter
                if (dictionary.contains(replaced)) {
                    val cost = TapEditDistance.substitutionCost(original, letter, tap, proximity, spatialModel)
                    offer(replaced, cost, edits = 1, 0, context, pool)
                }
            }
            val inserted = prefix + letter + suffixFrom
            if (dictionary.contains(inserted)) {
                offer(inserted, TapEditDistance.INSERT_COST, edits = 1, 0, context, pool)
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

        for (firstChar in buckets) {
            scanTwoEditBucket(typed, firstChar, taps, spatialModel, context, pool)
        }
    }

    private fun scanTwoEditBucket(
        typed: String,
        firstChar: Char,
        taps: List<TapPoint?>,
        spatialModel: Boolean,
        context: BigramTable.Context?,
        pool: MutableMap<String, Candidate>,
    ) {
        val table = dictionary.words
        for (index in dictionary.prefixRange(firstChar)) {
            val dist = TapEditDistance.withinTwo(typed, table, index)
            if (dist > 2) continue
            val word = table.wordAt(index)
            if (word !in pool) {
                val cost = TapEditDistance.weightedCost(typed, word, taps, proximity, spatialModel)
                if (cost <= MAX_TWO_EDIT_COST) {
                    offer(word, cost, edits = dist, extraChars = 0, context = context, pool = pool)
                }
            }
        }
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
        val bigram = context?.association(word.replace('ё', 'е')) ?: 0.0
        val extensionPenalty =
            if (edits == 0) {
                EXACT_EXTENSION_PENALTY * extraChars
            } else {
                FUZZY_EXTENSION_BASE + FUZZY_EXTENSION_PER_CHAR * extraChars
            }
        val score =
            ln(frequency.coerceAtLeast(MIN_FREQ)) +
                BIGRAM_WEIGHT * bigram -
                EDIT_WEIGHT * editCost -
                extensionPenalty
        val existing = pool[word]
        if (existing == null || score > existing.score) {
            pool[word] = Candidate(word, editCost, edits, extraChars, score)
        }
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
