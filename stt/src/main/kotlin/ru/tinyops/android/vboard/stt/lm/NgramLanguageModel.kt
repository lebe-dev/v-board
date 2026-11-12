package ru.tinyops.android.vboard.stt.lm

import kotlin.math.ln
import kotlin.math.min

/**
 * An interpolated n-gram language model (order 1-3 recommended) trained from
 * token sequences. Uses stupid backoff for context scoring and is intentionally
 * cheap: it is used for ranking candidates, not for likelihood estimation.
 */
class NgramLanguageModel(
    val order: Int = 3,
    private val backoffAlpha: Double = DEFAULT_BACKOFF_ALPHA,
    private val unknownPenaltyFactor: Double = DEFAULT_UNKNOWN_PENALTY_FACTOR,
) : LanguageModel {
    init {
        require(order in MIN_ORDER..MAX_ORDER) { "order must be in $MIN_ORDER..$MAX_ORDER" }
    }

    private val unigramCounts = HashMap<String, Double>()
    private val ngramCounts = HashMap<String, Double>()
    private val contextCounts = HashMap<String, Double>()
    private var totalTokens = 0.0

    override val trie: WordTrie = WordTrie()
    private var trieBuilt = false

    override val vocabularySize: Int get() = unigramCounts.size

    override val unknownLogProb: Double
        get() = ln(1.0 / ((totalTokens + 1.0) * unknownPenaltyFactor))

    /** Trains on a single token sequence. */
    fun addSentence(words: List<String>) {
        val tokens = words.map { it.lowercase() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return

        for (word in tokens) {
            unigramCounts.merge(word, 1.0, Double::plus)
            totalTokens += 1.0
        }
        countNgrams(tokens)
    }

    /** Trains on a whitespace-separated sentence. */
    fun addSentence(text: String) = addSentence(text.split(Regex("\\s+")))

    fun train(sentences: Iterable<String>) {
        for (sentence in sentences) addSentence(sentence)
        rebuildTrie()
    }

    /**
     * Rebuilds the vocabulary trie from the trained unigrams; call once after
     * all training is done. Re-inserting the same vocabulary is idempotent.
     */
    fun rebuildTrie() {
        for ((word, count) in unigramCounts) {
            trie.insert(word, ln(count / totalTokens), count)
        }
        trie.compact()
        trieBuilt = true
    }

    override fun unigramLogProb(word: String): Double = contextLogProb(emptyList(), word)

    override fun contextLogProb(
        context: List<String>,
        word: String,
    ): Double {
        if (!trieBuilt && unigramCounts.isNotEmpty()) rebuildTrie()

        val normalizedWord = word.lowercase()
        val normalizedContext = context.map { it.lowercase() }
        var lambda = 1.0

        for (n in min(order, normalizedContext.size + 1) downTo 1) {
            val score = ngramLogProb(n, normalizedContext, normalizedWord)
            if (score != null) return score + ln(lambda)
            lambda *= backoffAlpha
        }
        return unknownLogProb + ln(lambda)
    }

    /** Log-probability of [word] at the specific n-gram order, or null if unseen. */
    private fun ngramLogProb(
        n: Int,
        context: List<String>,
        word: String,
    ): Double? {
        if (n == 1) {
            val count = unigramCounts[word]
            return if (count != null && count > 0.0) ln(count / totalTokens) else null
        }
        val ctx = context.takeLast(n - 1)
        val count = ngramCounts[(ctx + word).joinToString(" ")]
        if (count == null || count <= 0.0) return null
        val denominator = contextCounts[ctx.joinToString(" ")] ?: 0.0
        return if (denominator > 0.0) ln(count / denominator) else null
    }

    private fun countNgrams(tokens: List<String>) {
        if (order < 2) return
        for (i in tokens.indices) {
            for (n in 2..order) {
                val start = i - n + 1
                if (start < 0) break
                ngramCounts.merge(tokens.subList(start, i + 1).joinToString(" "), 1.0, Double::plus)
                contextCounts.merge(tokens.subList(start, i).joinToString(" "), 1.0, Double::plus)
            }
        }
    }

    companion object {
        private const val MIN_ORDER = 1
        private const val MAX_ORDER = 5
        private const val DEFAULT_BACKOFF_ALPHA = 0.4
        private const val DEFAULT_UNKNOWN_PENALTY_FACTOR = 100.0
    }
}
