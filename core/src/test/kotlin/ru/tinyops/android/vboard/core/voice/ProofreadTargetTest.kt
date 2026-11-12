package ru.tinyops.android.vboard.core.voice

import ru.tinyops.android.vboard.core.input.CursorWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProofreadTargetTest {
    @Test
    fun `without a selection the whole text is taken`() {
        val target = ProofreadTarget.of(CursorWindow("привет мир", 3, 3, 0))
        assertEquals(ProofreadTarget(0, "привет мир"), target)
    }

    @Test
    fun `a selection is taken instead of the whole text`() {
        val target = ProofreadTarget.of(CursorWindow("привет мир", 7, 10, 0))
        assertEquals(ProofreadTarget(7, "мир"), target)
    }

    @Test
    fun `the position counts from the start of the editor text`() {
        val target = ProofreadTarget.of(CursorWindow("мир", 0, 3, 100))
        assertEquals(ProofreadTarget(100, "мир"), target)
    }

    @Test
    fun `whitespace around the text stays out of the request`() {
        val target = ProofreadTarget.of(CursorWindow("  привет \n", 3, 3, 0))
        assertEquals(ProofreadTarget(2, "привет"), target)
    }

    @Test
    fun `blank text has nothing to proofread`() {
        assertNull(ProofreadTarget.of(CursorWindow("   ", 1, 1, 0)))
        assertNull(ProofreadTarget.of(CursorWindow("a   b", 2, 3, 0)))
    }

    @Test
    fun `an unknown position in the editor cannot be replaced`() {
        assertNull(ProofreadTarget.of(CursorWindow("привет", 0, 0, -1)))
    }

    @Test
    fun `text longer than the limit on either side of the cursor is too long`() {
        assertTrue(ProofreadTarget.tooLong(CursorWindow("abcdef", 5, 5, 0), limit = 5))
        assertTrue(ProofreadTarget.tooLong(CursorWindow("abcdef", 1, 1, 0), limit = 5))
        assertFalse(ProofreadTarget.tooLong(CursorWindow("abcdef", 3, 3, 0), limit = 5))
    }

    @Test
    fun `a selection is never too long`() {
        assertFalse(ProofreadTarget.tooLong(CursorWindow("abcdef", 1, 6, 0), limit = 5))
    }

    @Test
    fun `the target is found in a later window of the same text`() {
        val target = ProofreadTarget(7, "мир")
        assertTrue(target.matches(CursorWindow("привет мир", 0, 0, 0)))
        assertTrue(target.matches(CursorWindow("ет мир", 0, 0, 4)))
        assertFalse(target.matches(CursorWindow("привет мор", 0, 0, 0)))
        assertFalse(target.matches(CursorWindow("при", 0, 0, 0)))
        assertFalse(target.matches(CursorWindow("мир", 0, 0, 8)))
    }
}
