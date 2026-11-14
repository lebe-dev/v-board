package ru.tinyops.android.vboard.core.input

import kotlin.test.Test
import kotlin.test.assertEquals

class CursorWindowTest {
    @Test
    fun `a known offset is kept`() {
        val window = CursorWindow("мир", 1, 1, 7).anchored(requestedBefore = 100)
        assertEquals(7, window.offset)
    }

    @Test
    fun `an unknown offset is the start when less than asked came before the cursor`() {
        val window = CursorWindow("привет мир", 3, 5, -1).anchored(requestedBefore = 100)
        assertEquals(0, window.offset)
        assertEquals(3, window.selectionStart)
        assertEquals(5, window.selectionEnd)
        assertEquals("привет мир", window.text)
    }

    @Test
    fun `an unknown offset stays unknown when the text before the cursor may go on`() {
        val window = CursorWindow("привет", 6, 6, -1).anchored(requestedBefore = 6)
        assertEquals(-1, window.offset)
    }
}
