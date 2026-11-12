package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.stt.lm.TrieNode
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * A best-first beam search over the decoder, limited to the words of a dictionary: at every step the
 * logits are renormalized over the tokens the dictionary allows after the prefix.
 *
 * A prefix with a single allowed continuation gets probability 1 whatever the model says, so the
 * model is not asked about it; this is where most of the decoder time would otherwise go.
 *
 * Renormalizing over the dictionary makes the search cheap but ignores the gesture wherever the
 * dictionary leaves one way on: such a letter counts as certain even if the finger never went
 * near it. So the [rescored] best words are ordered again by the probability the model gives the
 * whole word over all tokens, divided like the search score by the length to the power 0.5. Each
 * of them costs one decoder pass.
 *
 * With the previous word known, the rescored words also get its association with them from a
 * [BigramTable], weighted by [CONTEXT_WEIGHT]: the model alone often cannot tell endings apart
 * (`будет` and `будем`) or short words (`не`, `на`). More words are rescored then, [contextRescored].
 *
 * A letter the model has no token for (`ё` when training folded it into `е`) is reached through its
 * base letter. Such a hypothesis stands for several words at once: they share a score and come out
 * most frequent first.
 */
class NeuralWordSearch(
    private val spec: SwipeModelSpec,
    private val beamSize: Int = DEFAULT_BEAM_SIZE,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val rescored: Int = DEFAULT_RESCORED,
    private val contextRescored: Int = DEFAULT_CONTEXT_RESCORED,
) {
    private class Hypothesis(
        val tokens: LongArray,
        val score: Double,
        val nodes: List<TrieNode>,
    )

    /** Base letter to the letter reached through it. */
    private val folded: Map<Char, Char> =
        FOLDED_LETTERS.filterKeys { it !in spec.decoderTokens }.entries.associate { it.value to it.key }

    /**
     * The best words for the gesture the [model] has encoded, best first, at most [limit]; [context]
     * holds the associations with the word before the cursor, if it is known.
     */
    fun search(
        model: SwipeSequenceModel,
        root: TrieNode,
        limit: Int,
        context: BigramTable.Context? = null,
    ): List<String> {
        val partial = PriorityQueue<Hypothesis>(compareBy { it.score })
        val finished = ArrayList<Hypothesis>()
        partial += Hypothesis(longArrayOf(spec.sosId.toLong()), 0.0, listOf(root))
        val allowed = IntArray(spec.numClasses)
        while (partial.isNotEmpty()) {
            expand(model, partial.poll(), allowed, partial, finished)
            prune(partial)
        }
        finished.sortBy { it.score }
        rescore(model, finished, context)
        return words(finished, limit)
    }

    /**
     * Puts the first of the sorted [finished] in the order of [fullScore], less the weighted
     * association with the previous word when there is a [context].
     */
    private fun rescore(
        model: SwipeSequenceModel,
        finished: MutableList<Hypothesis>,
        context: BigramTable.Context?,
    ) {
        val count = minOf(if (context == null) rescored else contextRescored, finished.size)
        if (count < 2) return
        val best = finished.subList(0, count)
        val reordered =
            best
                .map { hypothesis ->
                    val association = context?.association(foldedWord(hypothesis.tokens)) ?: 0.0
                    hypothesis to fullScore(model, hypothesis.tokens) - CONTEXT_WEIGHT * association
                }.sortedBy { it.second }
                .map { it.first }
        for (index in reordered.indices) best[index] = reordered[index]
    }

    /** The letters of [tokens] without the start and end tokens, `ё` folded as in [BigramTable]. */
    private fun foldedWord(tokens: LongArray): String {
        val end = if (tokens.last() == spec.eosId.toLong()) tokens.size - 1 else tokens.size
        return buildString(end) {
            for (index in 1 until end) {
                val letter = spec.decoderTokens[tokens[index].toInt()]
                append(FOLDED_LETTERS[letter] ?: letter)
            }
        }
    }

    /** Like a search score, but with `log_softmax` over all tokens, as if there were no dictionary. */
    private fun fullScore(
        model: SwipeSequenceModel,
        tokens: LongArray,
    ): Double {
        val rows = tokens.size - 1
        val logits = model.logitsAfterEach(tokens, rows)
        val classes = spec.numClasses
        var logProb = 0.0
        for (row in 0 until rows) {
            val start = row * classes
            var largest = Double.NEGATIVE_INFINITY
            for (at in start until start + classes) largest = max(largest, logits[at].toDouble())
            var sum = 0.0
            for (at in start until start + classes) sum += exp(logits[at] - largest)
            logProb += logits[start + tokens[row + 1].toInt()] - largest - ln(sum)
        }
        return -logProb / tokens.size.toDouble().pow(LENGTH_PENALTY)
    }

    private fun expand(
        model: SwipeSequenceModel,
        hypothesis: Hypothesis,
        allowed: IntArray,
        partial: PriorityQueue<Hypothesis>,
        finished: MutableList<Hypothesis>,
    ) {
        val count = collectAllowed(hypothesis.nodes, allowed)
        if (count == 0) return
        val logProbs = if (count == 1) null else maskedLogProbs(model, hypothesis.tokens, allowed, count)
        val length = hypothesis.tokens.size
        val denormalized = hypothesis.score * length.toDouble().pow(LENGTH_PENALTY)
        for (slot in slotsToContinue(logProbs, count)) {
            val token = allowed[slot]
            val logProb = logProbs?.get(slot) ?: 0.0
            val score = (denormalized - logProb) / (length + 1).toDouble().pow(LENGTH_PENALTY)
            val tokens = hypothesis.tokens.copyOf(length + 1).also { it[length] = token.toLong() }
            val isEnd = token == spec.eosId
            val next = Hypothesis(tokens, score, if (isEnd) hypothesis.nodes else advance(hypothesis.nodes, token))
            if (isEnd || length >= maxSteps) finished += next else partial += next
        }
    }

    /** The slots to continue with: the [beamSize] most probable of those allowed. */
    private fun slotsToContinue(
        logProbs: DoubleArray?,
        count: Int,
    ): List<Int> {
        if (logProbs == null || count <= beamSize) return (0 until count).toList()
        return (0 until count).sortedByDescending { logProbs[it] }.take(beamSize)
    }

    private fun prune(partial: PriorityQueue<Hypothesis>) {
        if (partial.size <= beamSize) return
        val best = ArrayList<Hypothesis>(beamSize)
        repeat(beamSize) { best += partial.poll() }
        partial.clear()
        partial += best
    }

    private fun collectAllowed(
        nodes: List<TrieNode>,
        allowed: IntArray,
    ): Int {
        var count = 0
        for (token in spec.decoderTokens.indices) {
            val letter = spec.decoderTokens[token]
            val alternative = folded[letter]
            val reachable =
                nodes.any { node ->
                    node.child(letter) != null || (alternative != null && node.child(alternative) != null)
                }
            if (reachable) allowed[count++] = token
        }
        if (nodes.any { it.isWord }) allowed[count++] = spec.eosId
        return count
    }

    private fun advance(
        nodes: List<TrieNode>,
        token: Int,
    ): List<TrieNode> {
        val letter = spec.decoderTokens[token]
        val alternative = folded[letter]
        return buildList {
            for (node in nodes) {
                node.child(letter)?.let(::add)
                alternative?.let(node::child)?.let(::add)
            }
        }
    }

    /** `log_softmax` over the allowed tokens only. */
    private fun maskedLogProbs(
        model: SwipeSequenceModel,
        tokens: LongArray,
        allowed: IntArray,
        count: Int,
    ): DoubleArray {
        val logits = model.nextLogits(tokens, tokens.size)
        var largest = Double.NEGATIVE_INFINITY
        for (slot in 0 until count) largest = max(largest, logits[allowed[slot]].toDouble())
        var sum = 0.0
        for (slot in 0 until count) sum += exp(logits[allowed[slot]] - largest)
        val logSum = largest + ln(sum)
        return DoubleArray(count) { logits[allowed[it]] - logSum }
    }

    /** The words of the [finished] hypotheses, which are already in their final order. */
    private fun words(
        finished: List<Hypothesis>,
        limit: Int,
    ): List<String> {
        val result = LinkedHashSet<String>()
        for (hypothesis in finished) {
            val words = hypothesis.nodes.filter { it.isWord }.sortedByDescending { it.frequency }
            for (node in words) {
                if (result.size == limit) return result.toList()
                node.word?.let(result::add)
            }
        }
        return result.toList()
    }

    companion object {
        /** On the NeuroSwipe validation set, 4, 6, 10 and 16 find the same top-1 words. */
        const val DEFAULT_BEAM_SIZE = 4

        /** Rescoring more words than are shown changed almost nothing on the validation set. */
        const val DEFAULT_RESCORED = 4

        /**
         * With a previous word the right word is found further down more often: 6 words gained 0.2
         * points of top-1 over 4 on the validation set, 8 only 0.06 more.
         */
        const val DEFAULT_CONTEXT_RESCORED = 6

        /** The weight of the association with the previous word against the model's score; 0.3 to 0.5 measure alike. */
        const val CONTEXT_WEIGHT = 0.4

        /** The longest word the positional encoding of the decoder allows. */
        const val DEFAULT_MAX_STEPS = 34
        private const val LENGTH_PENALTY = 0.5

        /** Letter to its base letter, for models trained without the letter. */
        private val FOLDED_LETTERS = mapOf('ё' to 'е')
    }
}
