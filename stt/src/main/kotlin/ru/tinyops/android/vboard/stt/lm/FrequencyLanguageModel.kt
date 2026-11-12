package ru.tinyops.android.vboard.stt.lm

import kotlin.math.ln

/**
 * A unigram language model built from word frequencies. Unknown words receive a
 * fixed penalty derived from the corpus size so that in-vocabulary words always
 * rank above them. The [trie] is the model's only storage.
 */
class FrequencyLanguageModel private constructor(
    override val trie: WordTrie,
    override val unknownLogProb: Double,
) : LanguageModel {
    override val vocabularySize: Int get() = trie.wordCount

    override val scoresFromTrie: Boolean get() = true

    override fun unigramLogProb(word: String): Double = trie.logProbOrDefault(word.lowercase(), unknownLogProb)

    override fun contextLogProb(
        context: List<String>,
        word: String,
    ): Double = unigramLogProb(word)

    companion object {
        /** A model over a trie that is already built, typically restored from a file. */
        fun fromTrie(
            trie: WordTrie,
            unknownLogProb: Double,
        ): FrequencyLanguageModel = FrequencyLanguageModel(trie, unknownLogProb)

        /** Builds a model from a `word -> frequency` map. Frequencies must be positive. */
        fun fromFrequencies(
            frequencies: Map<String, Double>,
            unknownPenaltyFactor: Double = 100.0,
        ): FrequencyLanguageModel {
            val normalized = frequencies.mapKeys { it.key.lowercase() }
            return fromWords(
                normalized.keys.toTypedArray(),
                normalized.values.toDoubleArray(),
                unknownPenaltyFactor,
            )
        }

        /**
         * Builds a model from parallel arrays without intermediate maps, for large vocabularies.
         * Words must be distinct and lowercase; frequencies must be positive.
         */
        fun fromWords(
            words: Array<String>,
            frequencies: DoubleArray,
            unknownPenaltyFactor: Double = 100.0,
        ): FrequencyLanguageModel {
            require(words.isNotEmpty()) { "frequencies must not be empty" }
            require(words.size == frequencies.size) { "words and frequencies must have the same size" }
            val total = frequencies.sum()
            require(total > 0.0) { "total frequency must be positive" }
            val trie = WordTrie()
            for (i in words.indices) trie.insert(words[i], ln(frequencies[i] / total), frequencies[i])
            trie.compact()
            return FrequencyLanguageModel(trie, ln(1.0 / (total * unknownPenaltyFactor)))
        }
    }
}
