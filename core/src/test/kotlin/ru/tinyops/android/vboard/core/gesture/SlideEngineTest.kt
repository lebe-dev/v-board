package ru.tinyops.android.vboard.core.gesture

import ru.tinyops.android.vboard.core.layout.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlideEngineTest {
    @Test
    fun `an unknown or missing code means the default engine`() {
        assertEquals(SlideEngine.DEFAULT, SlideEngine.fromCode(null))
        assertEquals(SlideEngine.DEFAULT, SlideEngine.fromCode("removed-engine"))
    }

    @Test
    fun `every engine survives a round trip through its code`() {
        for (engine in SlideEngine.entries) assertEquals(engine, SlideEngine.fromCode(engine.code))
    }

    @Test
    fun `the experimental engine covers russian only`() {
        assertTrue(SlideEngine.EXPERIMENTAL.usesNeuralModel(Language.RUSSIAN))
        assertFalse(SlideEngine.EXPERIMENTAL.usesNeuralModel(Language.ENGLISH))
        assertFalse(SlideEngine.DEFAULT.usesNeuralModel(Language.RUSSIAN))
    }
}
