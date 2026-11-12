package ru.tinyops.android.vboard.core.gesture.neural

import ru.tinyops.android.vboard.core.gesture.GestureLayoutFactory
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.core.phoneGeometry
import ru.tinyops.android.vboard.core.swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeuralGestureEngineTest {
    private val geometry = phoneGeometry(Language.RUSSIAN)
    private val layout = GestureLayoutFactory.create(geometry, xdpi = 420f, ydpi = 420f)
    private val trie = trieOf("дом" to 1.0)

    private fun engine(model: SwipeSequenceModel) = NeuralGestureEngine(TEST_SPEC, model)

    @Test
    fun `a gesture goes through the model and comes back as words`() {
        val model = ScriptedModel(TEST_SPEC, logits = emptyMap())
        val engine = engine(model).apply { setLayout(layout) }
        assertEquals(listOf("дом"), engine.decode(swipe(geometry, "дом"), trie))
        assertTrue(model.encodedLength > 0)
    }

    @Test
    fun `without a layout there are no words`() {
        val engine = engine(ScriptedModel(TEST_SPEC, logits = emptyMap()))
        assertEquals(emptyList(), engine.decode(swipe(geometry, "дом"), trie))
    }
}
