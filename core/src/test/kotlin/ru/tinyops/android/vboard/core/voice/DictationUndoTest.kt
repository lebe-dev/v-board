package ru.tinyops.android.vboard.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationUndoTest {
    @Test
    fun `the whole dictation is undone at once`() {
        val undo = DictationUndo()
        undo.start()
        undo.add("Привет")
        undo.add(" как дела")
        assertFalse(undo.available)
        undo.finish()
        assertTrue(undo.available)
        assertEquals(15, undo.lengthBefore("Текст. Привет как дела"))
    }

    @Test
    fun `nothing to undo after a dictation without text`() {
        val undo = DictationUndo()
        undo.start()
        undo.finish()
        assertFalse(undo.available)
    }

    @Test
    fun `text changed after the dictation gives the undo up`() {
        val undo = DictationUndo()
        undo.start()
        undo.add(" привет")
        undo.finish()
        assertNull(undo.lengthBefore(" привет!"))
        assertNull(undo.lengthBefore(null))
    }

    @Test
    fun `typing during the dictation leaves nothing to undo`() {
        val undo = DictationUndo()
        undo.start()
        undo.add("один")
        undo.reset()
        undo.add(" два")
        undo.finish()
        assertFalse(undo.available)
    }

    @Test
    fun `text inserted outside a dictation is not recorded`() {
        val undo = DictationUndo()
        undo.add("вставка")
        undo.finish()
        assertFalse(undo.available)
    }

    @Test
    fun `a new dictation replaces the previous undo`() {
        val undo = DictationUndo()
        undo.start()
        undo.add("первая")
        undo.finish()
        undo.start()
        assertFalse(undo.available)
        undo.add(" вторая")
        undo.finish()
        assertEquals(7, undo.lengthBefore("первая вторая"))
    }

    @Test
    fun `reset ends the chance to undo`() {
        val undo = DictationUndo()
        undo.start()
        undo.add("текст")
        undo.finish()
        undo.reset()
        assertFalse(undo.available)
        assertNull(undo.lengthBefore("текст"))
    }
}
