package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.core.phoneGeometry
import ru.tinyops.android.vboard.core.swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end: real key geometry, bundled dictionaries and the stt decoder. */
class GestureDecodingTest {
    private fun engine(
        language: Language,
    ): Pair<GestureEngine, ru.tinyops.android.vboard.core.layout.KeyboardGeometry> {
        val geometry = phoneGeometry(language)
        val engine =
            GestureEngine().apply {
                setLayout(GestureLayoutFactory.create(geometry, xdpi = 420f, ydpi = 420f))
                setLanguageModel(bundledDictionary(language).languageModel)
            }
        return engine to geometry
    }

    @Test
    fun `decodes english words`() {
        val (engine, geometry) = engine(Language.ENGLISH)
        for (word in listOf("hello", "world", "keyboard", "thanks", "people", "about")) {
            val result = engine.decode(swipe(geometry, word), "", complete = true)
            assertTrue(word in result.take(3), "$word -> $result")
        }
    }

    @Test
    fun `decodes russian words`() {
        val (engine, geometry) = engine(Language.RUSSIAN)
        for (word in listOf("привет", "спасибо", "хорошо", "клавиатуру", "сегодня", "работа")) {
            val result = engine.decode(swipe(geometry, word), "", complete = true)
            assertTrue(word in result.take(3), "$word -> $result")
        }
    }

    @Test
    fun `swipes through soft sign reach words with a hard sign`() {
        val (engine, geometry) = engine(Language.RUSSIAN)
        // "объяснить" is swiped as "обьяснить": ъ shares the ь key.
        val result = engine.decode(swipe(geometry, "обьяснить"), "", complete = true)
        assertTrue("объяснить" in result.take(3), "$result")
    }

    @Test
    fun `alias keys reuse the geometry of their base key`() {
        val geometry = phoneGeometry(Language.RUSSIAN)
        val layout = GestureLayoutFactory.create(geometry, 420f, 420f)
        val soft = layout.keys.first { it.label == "ь" }
        val hard = layout.keys.first { it.label == "ъ" }
        assertEquals(soft.centerX, hard.centerX)
        assertEquals(33, layout.keys.size)
    }

    @Test
    fun `not ready without a model`() {
        val engine = GestureEngine()
        assertTrue(engine.decode(swipe(phoneGeometry(Language.ENGLISH), "hello"), "", true).isEmpty())
    }
}
