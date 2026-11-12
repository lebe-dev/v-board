package ru.tinyops.android.vboard.stt.lm

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrequencyLanguageModelTest {
    @Test
    fun `unigram probability follows corpus frequency`() {
        val model = FrequencyLanguageModel.fromFrequencies(mapOf("a" to 3.0, "b" to 1.0))
        assertEquals(ln(0.75), model.unigramLogProb("a"), 1e-12)
        assertEquals(ln(0.25), model.unigramLogProb("b"), 1e-12)
        assertTrue(model.unigramLogProb("a") > model.unigramLogProb("b"))
    }

    @Test
    fun `unknown words are penalized below any known word`() {
        val model = FrequencyLanguageModel.fromFrequencies(mapOf("a" to 3.0, "b" to 1.0))
        val unknown = model.unigramLogProb("zzz")
        assertEquals(model.unknownLogProb, unknown, 1e-12)
        assertTrue(unknown < model.unigramLogProb("b"))
    }

    @Test
    fun `lookup is case-insensitive`() {
        val model = FrequencyLanguageModel.fromFrequencies(mapOf("Hello" to 1.0))
        assertEquals(model.unigramLogProb("hello"), model.unigramLogProb("HELLO"), 1e-12)
    }

    @Test
    fun `trie contains the vocabulary`() {
        val model = FrequencyLanguageModel.fromFrequencies(mapOf("cat" to 1.0, "dog" to 2.0))
        assertEquals(2, model.vocabularySize)
        assertTrue(model.trie.contains("cat"))
        assertTrue(model.trie.contains("dog"))
    }

    @Test
    fun `parallel arrays build the same model as a map`() {
        val fromMap = FrequencyLanguageModel.fromFrequencies(mapOf("cat" to 3.0, "car" to 1.0))
        val fromArrays = FrequencyLanguageModel.fromWords(arrayOf("cat", "car"), doubleArrayOf(3.0, 1.0))
        assertEquals(fromMap.vocabularySize, fromArrays.vocabularySize)
        assertEquals(fromMap.unknownLogProb, fromArrays.unknownLogProb, 1e-12)
        assertEquals(fromMap.unigramLogProb("cat"), fromArrays.unigramLogProb("cat"), 1e-12)
        assertEquals(fromMap.unigramLogProb("car"), fromArrays.unigramLogProb("car"), 1e-12)
    }

    @Test
    fun `a prefix of a known word is unknown`() {
        val model = FrequencyLanguageModel.fromFrequencies(mapOf("cat" to 1.0))
        assertEquals(model.unknownLogProb, model.unigramLogProb("ca"), 1e-12)
    }

    @Test
    fun `empty corpus is rejected`() {
        try {
            FrequencyLanguageModel.fromFrequencies(emptyMap())
            error("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }
}
