package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.dict.Dictionary
import ru.tinyops.android.vboard.core.dict.bigramTableOf
import ru.tinyops.android.vboard.core.gesture.neural.NeuralGestureEngine
import ru.tinyops.android.vboard.core.gesture.neural.ScriptedModel
import ru.tinyops.android.vboard.core.gesture.neural.SwipeFeatures
import ru.tinyops.android.vboard.core.gesture.neural.SwipeSequenceModel
import ru.tinyops.android.vboard.core.gesture.neural.TEST_SPEC
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.core.phoneGeometry
import ru.tinyops.android.vboard.core.swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwitchableGestureEngineTest {
    private val geometry = phoneGeometry(Language.RUSSIAN)
    private val layout = GestureLayoutFactory.create(geometry, xdpi = 420f, ydpi = 420f)
    private val points = swipe(geometry, "привет")

    /** Has no opinion on any letter, so only whether it was asked matters. */
    private val alwaysFirstLetter = ScriptedModel(TEST_SPEC, logits = emptyMap())

    private class BrokenModel : SwipeSequenceModel {
        var calls = 0

        override fun encode(features: SwipeFeatures) {
            calls++
            error("The native runtime failed")
        }

        override fun nextLogits(
            tokens: LongArray,
            length: Int,
        ): FloatArray = error("Not expected")

        override fun logitsAfterEach(
            tokens: LongArray,
            length: Int,
        ): FloatArray = error("Not expected")
    }

    /** Fails the way a native library does: with an [Error], not an [Exception]. */
    private class LinkageBrokenModel : SwipeSequenceModel {
        override fun encode(features: SwipeFeatures): Unit = throw UnsatisfiedLinkError("No implementation found")

        override fun nextLogits(
            tokens: LongArray,
            length: Int,
        ): FloatArray = error("Not expected")

        override fun logitsAfterEach(
            tokens: LongArray,
            length: Int,
        ): FloatArray = error("Not expected")
    }

    private var opened = 0
    private val failures = ArrayList<Throwable>()

    private fun engine(
        language: Language = Language.RUSSIAN,
        model: SwipeSequenceModel? = alwaysFirstLetter,
    ) = SwitchableGestureEngine(
        language = language,
        openNeural = {
            opened++
            model?.let { NeuralGestureEngine(TEST_SPEC, it) }
        },
        onNeuralFailure = { failures += it },
    ).apply {
        setLayout(layout)
        setLanguageModel(bundledDictionary(Language.RUSSIAN).languageModel)
    }

    private val classicWords by lazy { engine().decode(points, "", complete = true) }

    @Test
    fun `the default engine never opens the model`() {
        val engine = engine()
        assertEquals("привет", engine.decode(points, "", complete = true).first())
        assertEquals(0, opened)
        assertFalse(engine.routesToNeural(complete = true))
    }

    @Test
    fun `the experimental engine decodes the finished gesture`() {
        val engine = engine().apply { slideEngine = SlideEngine.EXPERIMENTAL }
        val words = engine.decode(points, "", complete = true)
        assertTrue(alwaysFirstLetter.encodedLength > 0)
        assertTrue(words.isNotEmpty())
        assertTrue(engine.routesToNeural(complete = true))
    }

    @Test
    fun `live suggestions stay with the default decoder`() {
        val engine = engine().apply { slideEngine = SlideEngine.EXPERIMENTAL }
        engine.decode(points, "", complete = false)
        assertEquals(0, alwaysFirstLetter.encodedLength)
        assertFalse(engine.routesToNeural(complete = false))
    }

    @Test
    fun `a language the model does not know keeps the default decoder`() {
        val engine = engine(language = Language.ENGLISH).apply { slideEngine = SlideEngine.EXPERIMENTAL }
        engine.decode(points, "", complete = true)
        assertEquals(0, opened)
    }

    @Test
    fun `a build without the model falls back to the default decoder`() {
        val engine = engine(model = null).apply { slideEngine = SlideEngine.EXPERIMENTAL }
        assertEquals(classicWords, engine.decode(points, "", complete = true))
        assertFalse(engine.routesToNeural(complete = true))
    }

    @Test
    fun `a failing model is reported once and then left alone`() {
        val broken = BrokenModel()
        val engine = engine(model = broken).apply { slideEngine = SlideEngine.EXPERIMENTAL }
        assertEquals(classicWords, engine.decode(points, "", complete = true))
        assertEquals(classicWords, engine.decode(points, "", complete = true))
        assertEquals(1, broken.calls)
        assertEquals(1, failures.size)
    }

    @Test
    fun `preparing opens the model once`() {
        val engine = engine().apply { slideEngine = SlideEngine.EXPERIMENTAL }
        engine.prepare()
        engine.prepare()
        engine.decode(points, "", complete = true)
        assertEquals(1, opened)
    }

    @Test
    fun `switching back returns to the default decoder without losing the model`() {
        val engine = engine().apply { slideEngine = SlideEngine.EXPERIMENTAL }
        engine.prepare()
        engine.slideEngine = SlideEngine.DEFAULT
        assertEquals(classicWords, engine.decode(points, "", complete = true))
        engine.slideEngine = SlideEngine.EXPERIMENTAL
        engine.prepare()
        assertEquals(1, opened)
    }

    @Test
    fun `an error while opening the model falls back to the default decoder`() {
        val engine =
            SwitchableGestureEngine(
                language = Language.RUSSIAN,
                openNeural = { throw UnsatisfiedLinkError("dlopen failed") },
                onNeuralFailure = { failures += it },
            ).apply {
                setLayout(layout)
                setLanguageModel(bundledDictionary(Language.RUSSIAN).languageModel)
                slideEngine = SlideEngine.EXPERIMENTAL
            }
        assertEquals(classicWords, engine.decode(points, "", complete = true))
        assertFalse(engine.routesToNeural(complete = true))
        assertEquals(1, failures.size)
    }

    @Test
    fun `the experimental engine weighs the word before the cursor`() {
        val tie =
            ScriptedModel(
                TEST_SPEC,
                logits = mapOf("" to mapOf('а' to 5f), "а" to mapOf('б' to 5f, 'в' to 5f)),
                eos = mapOf("аб" to 5f, "ав" to 5f),
            )
        val engine =
            engine(model = tie).apply {
                slideEngine = SlideEngine.EXPERIMENTAL
                setLanguageModel(Dictionary.parse("аб\t1\nав\t1".byteInputStream()).languageModel)
            }
        assertEquals("аб", engine.decode(points, "Мы ", complete = true).first(), "no table yet")
        engine.setBigrams(BigramTable.read(bigramTableOf(mapOf(("мы" to "ав") to 50L, ("мы" to "аб") to 1L))))
        assertEquals("ав", engine.decode(points, "Мы ", complete = true).first())
        assertEquals("аб", engine.decode(points, "Мы. ", complete = true).first(), "a new sentence has no context")
        assertEquals("аб", engine.decode(points, "", complete = true).first())
    }

    @Test
    fun `an error while decoding falls back to the default decoder`() {
        val engine = engine(model = LinkageBrokenModel()).apply { slideEngine = SlideEngine.EXPERIMENTAL }
        assertEquals(classicWords, engine.decode(points, "", complete = true))
        assertEquals(1, failures.size)
    }
}
