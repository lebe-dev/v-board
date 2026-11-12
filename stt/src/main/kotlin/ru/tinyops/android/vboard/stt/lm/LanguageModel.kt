package ru.tinyops.android.vboard.stt.lm

/**
 * A language model backing the gesture decoder.
 *
 * Scores are natural-log probabilities (non-positive). The [trie] defines the
 * searchable vocabulary.
 */
interface LanguageModel {
    /** Vocabulary trie used to constrain the beam search. */
    val trie: WordTrie

    /** Size of the vocabulary. */
    val vocabularySize: Int

    /** Log-probability assigned to a word outside the vocabulary. */
    val unknownLogProb: Double

    /**
     * Whether [contextLogProb] of every vocabulary word is simply its log-probability in the
     * [trie]. The decoder then reads scores from the trie without materializing candidate words.
     */
    val scoresFromTrie: Boolean get() = false

    /** Log-probability of [word] with no context. */
    fun unigramLogProb(word: String): Double

    /** Log-probability of [word] given the preceding [context] words (oldest first). */
    fun contextLogProb(
        context: List<String>,
        word: String,
    ): Double
}
