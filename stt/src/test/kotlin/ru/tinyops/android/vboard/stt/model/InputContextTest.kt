package ru.tinyops.android.vboard.stt.model

import kotlin.test.Test
import kotlin.test.assertEquals

class InputContextTest {
    @Test
    fun `context words are split up to the cursor`() {
        val context = InputContext(precedingText = "say hello now", cursorPosition = 9)
        assertEquals(listOf("say", "hello"), context.contextWords())
    }

    @Test
    fun `empty text yields no context words`() {
        assertEquals(emptyList(), InputContext().contextWords())
        assertEquals(emptyList(), InputContext(precedingText = "   ").contextWords())
    }

    @Test
    fun `cursor position is clamped`() {
        val context = InputContext(precedingText = "hi", cursorPosition = 99)
        assertEquals(listOf("hi"), context.contextWords())
        val negative = InputContext(precedingText = "hi", cursorPosition = -5)
        assertEquals(emptyList(), negative.contextWords())
    }
}
