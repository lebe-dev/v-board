package ru.tinyops.android.vboard.stt.decode

import ru.tinyops.android.vboard.stt.TEST_FREQUENCIES
import ru.tinyops.android.vboard.stt.gestureForWord
import ru.tinyops.android.vboard.stt.lm.FrequencyLanguageModel
import ru.tinyops.android.vboard.stt.lm.LanguageModel
import ru.tinyops.android.vboard.stt.lm.WordTrie
import ru.tinyops.android.vboard.stt.model.InputContext
import ru.tinyops.android.vboard.stt.model.TouchAction
import ru.tinyops.android.vboard.stt.model.TouchPoint
import ru.tinyops.android.vboard.stt.qwertyLayout
import ru.tinyops.android.vboard.stt.withWobble
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BeamSearchDecoderTest {
    private val layout = qwertyLayout()

    private fun decoder(config: DecodeConfig = DecodeConfig(maxResults = 5)): BeamSearchDecoder =
        BeamSearchDecoder(config).apply {
            setKeyboardLayout(layout)
            loadLanguageModel(FrequencyLanguageModel.fromFrequencies(TEST_FREQUENCIES))
            startSession(InputContext())
        }

    @Test
    fun `decodes a straight horizontal word`() {
        val suggestions = decoder().onGestureEnd(gestureForWord(layout, "the"))
        assertEquals("the", suggestions.first().word, "suggestions=$suggestions")
    }

    @Test
    fun `decodes hello`() {
        val suggestions = decoder().onGestureEnd(gestureForWord(layout, "hello"))
        assertEquals("hello", suggestions.first().word, "suggestions=$suggestions")
    }

    @Test
    fun `decodes world`() {
        val suggestions = decoder().onGestureEnd(gestureForWord(layout, "world"))
        assertEquals("world", suggestions.first().word, "suggestions=$suggestions")
    }

    @Test
    fun `distinguishes the final letter`() {
        val suggestions = decoder().onGestureEnd(gestureForWord(layout, "hello"))
        val words = suggestions.map { it.word }
        assertTrue(words.indexOf("hello") < words.indexOf("help"), "words=$words")
    }

    @Test
    fun `is robust to a small finger wobble`() {
        val gesture = gestureForWord(layout, "hello").withWobble(amplitude = 6f, period = 3)
        val suggestions = decoder().onGestureEnd(gesture)
        assertEquals("hello", suggestions.first().word, "suggestions=$suggestions")
    }

    @Test
    fun `preemptive decode returns suggestions for a partial gesture`() {
        val full = gestureForWord(layout, "hello")
        val partial = full.subList(0, full.size / 2)
        val suggestions = decoder().onGestureUpdate(partial)
        assertTrue(suggestions.isNotEmpty(), "expected live suggestions")
    }

    @Test
    fun `keeps the correct prefix when the vocabulary is larger than the beam`() {
        // Prefixes must be ranked by how well they explain the start of the gesture: ranking
        // them as if their last letter ended the gesture drops "hel..." once the beam is narrow.
        val config = DecodeConfig(beamWidth = 3, maxResults = 5)
        for (word in listOf("hello", "world", "would")) {
            val suggestions = decoder(config).onGestureEnd(gestureForWord(layout, word))
            assertEquals(word, suggestions.firstOrNull()?.word, "$word -> $suggestions")
        }
    }

    @Test
    fun `respects maxResults`() {
        val config = DecodeConfig(maxResults = 2)
        val suggestions = decoder(config).onGestureEnd(gestureForWord(layout, "hello"))
        assertTrue(suggestions.size <= 2, "suggestions=$suggestions")
    }

    @Test
    fun `suggestions are ordered by score`() {
        val suggestions = decoder().onGestureEnd(gestureForWord(layout, "world"))
        val scores = suggestions.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun `returns empty when no language model is loaded`() {
        val decoder = BeamSearchDecoder()
        decoder.setKeyboardLayout(layout)
        assertTrue(decoder.onGestureEnd(gestureForWord(layout, "hello")).isEmpty())
    }

    @Test
    fun `returns empty when no layout is set`() {
        val decoder = BeamSearchDecoder()
        decoder.loadLanguageModel(FrequencyLanguageModel.fromFrequencies(TEST_FREQUENCIES))
        assertTrue(decoder.onGestureEnd(gestureForWord(layout, "hello")).isEmpty())
    }

    @Test
    fun `returns empty for a degenerate gesture`() {
        val decoder = decoder()
        val single = listOf(TouchPoint(TouchAction.DOWN, 0, 50f, 50f, 0.0))
        assertTrue(decoder.onGestureEnd(single).isEmpty())
    }

    @Test
    fun `passes the editing context to the language model`() {
        val model = RecordingLanguageModel()
        val decoder =
            BeamSearchDecoder(DecodeConfig(maxResults = 3)).apply {
                setKeyboardLayout(layout)
                loadLanguageModel(model)
                startSession(InputContext(precedingText = "say ", cursorPosition = 4))
            }
        decoder.onGestureEnd(gestureForWord(layout, "hello"))
        assertEquals(listOf("say"), model.capturedContext)
    }

    private class RecordingLanguageModel : LanguageModel {
        override val trie: WordTrie =
            WordTrie().apply {
                insert("hello", logProb = 0.0)
                insert("hell", logProb = -1.0)
            }
        override val vocabularySize: Int = 2
        override val unknownLogProb: Double = -50.0
        var capturedContext: List<String>? = null

        override fun unigramLogProb(word: String): Double = if (trie.contains(word.lowercase())) 0.0 else unknownLogProb

        override fun contextLogProb(
            context: List<String>,
            word: String,
        ): Double {
            capturedContext = context
            return unigramLogProb(word)
        }
    }

    @Test
    fun `a reused decoder answers like a fresh one whatever gesture came before`() {
        val reused = decoder()
        val gestures =
            listOf("internationalization", "the", "hello", "world", "the", "help")
                .map { gestureForWord(layout, it).withWobble(amplitude = 4f, period = 3) }
        for (gesture in gestures + gestures.reversed()) {
            assertEquals(decoder().onGestureEnd(gesture), reused.onGestureEnd(gesture))
        }
    }

    @Test
    fun `a narrow beam keeps the same candidates as a reused decoder`() {
        val config = DecodeConfig(beamWidth = 2, maxResults = 5)
        val reused = decoder(config)
        val gesture = gestureForWord(layout, "hello")
        val first = reused.onGestureEnd(gesture)
        assertEquals(first, reused.onGestureEnd(gesture))
        assertEquals(first, decoder(config).onGestureEnd(gesture))
    }

    @Test
    fun `a changed config applies to the next decode`() {
        val decoder = decoder()
        val gesture = gestureForWord(layout, "hello")
        assertTrue(decoder.onGestureEnd(gesture).size > 1)
        decoder.config = DecodeConfig(maxResults = 1)
        assertEquals(1, decoder.onGestureEnd(gesture).size)
    }
}
