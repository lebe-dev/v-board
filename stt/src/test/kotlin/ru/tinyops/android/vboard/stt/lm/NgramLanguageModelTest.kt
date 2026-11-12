package ru.tinyops.android.vboard.stt.lm

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NgramLanguageModelTest {
    private fun bigramModel(): NgramLanguageModel {
        val model = NgramLanguageModel(order = 2)
        model.train(
            listOf(
                "the cat sat",
                "the cat ran",
                "a dog ran",
            ),
        )
        return model
    }

    @Test
    fun `unigram scores match token frequency`() {
        val model = bigramModel()
        // "the" appears twice, "sat" once, out of 9 tokens.
        assertEquals(ln(2.0 / 9.0), model.unigramLogProb("the"), 1e-12)
        assertEquals(ln(1.0 / 9.0), model.unigramLogProb("sat"), 1e-12)
    }

    @Test
    fun `bigram context raises the score of a seen continuation`() {
        val model = bigramModel()
        val withContext = model.contextLogProb(listOf("the"), "cat")
        val withoutContext = model.unigramLogProb("cat")
        assertTrue(withContext > withoutContext, "context=$withContext unigram=$withoutContext")
        assertEquals(0.0, withContext, 1e-12)
    }

    @Test
    fun `unseen context backs off below the unigram estimate`() {
        val model = bigramModel()
        val backedOff = model.contextLogProb(listOf("dog"), "the")
        val unigram = model.unigramLogProb("the")
        assertTrue(backedOff < unigram, "backedOff=$backedOff unigram=$unigram")
    }

    @Test
    fun `unknown words score below any known word`() {
        val model = bigramModel()
        val unknown = model.contextLogProb(emptyList(), "zzz")
        assertTrue(unknown < model.unigramLogProb("sat"), "unknown=$unknown")
    }

    @Test
    fun `order one behaves like a unigram model`() {
        val model = NgramLanguageModel(order = 1)
        model.train(listOf("a a b"))
        assertEquals(ln(2.0 / 3.0), model.unigramLogProb("a"), 1e-12)
        // context is ignored
        assertEquals(
            model.unigramLogProb("b"),
            model.contextLogProb(listOf("a"), "b"),
            1e-12,
        )
    }

    @Test
    fun `trigram context is used when available`() {
        val model = NgramLanguageModel(order = 3)
        model.train(
            listOf(
                "i love pizza",
                "you love pasta",
            ),
        )
        val trigram = model.contextLogProb(listOf("i", "love"), "pizza")
        val bigram = model.contextLogProb(listOf("love"), "pizza")
        assertTrue(trigram > bigram, "trigram=$trigram bigram=$bigram")
    }

    @Test
    fun `trie is rebuilt with the trained vocabulary`() {
        val model = bigramModel()
        assertTrue(model.vocabularySize > 0)
        assertTrue(model.trie.contains("cat"))
        assertTrue(model.trie.contains("dog"))
    }

    @Test
    fun `scores are lower for less likely words`() {
        val model = bigramModel()
        assertTrue(model.unigramLogProb("the") > model.unigramLogProb("sat"))
    }
}
