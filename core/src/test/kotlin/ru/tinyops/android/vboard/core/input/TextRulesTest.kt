package ru.tinyops.android.vboard.core.input

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextRulesTest {
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun `space is needed after a word but not after whitespace or an opening bracket`() {
        assertTrue(TextRules.needsSpaceBefore("hello"))
        assertTrue(TextRules.needsSpaceBefore("end."))
        assertFalse(TextRules.needsSpaceBefore(""))
        assertFalse(TextRules.needsSpaceBefore(null))
        assertFalse(TextRules.needsSpaceBefore("hello "))
        assertFalse(TextRules.needsSpaceBefore("("))
        assertFalse(TextRules.needsSpaceBefore("line\n"))
    }

    @Test
    fun `a word is deleted together with the spaces after it`() {
        assertEquals("world ".length, TextRules.wordBeforeLength("hello world "))
        assertEquals("мир".length, TextRules.wordBeforeLength("привет мир"))
        assertEquals("кто-то".length, TextRules.wordBeforeLength("вот кто-то"))
        assertEquals("don't".length, TextRules.wordBeforeLength("I don't"))
    }

    @Test
    fun `punctuation goes apart from the word before it`() {
        assertEquals(", ".length, TextRules.wordBeforeLength("hello, "))
        assertEquals("...".length, TextRules.wordBeforeLength("wait..."))
        assertEquals("-".length, TextRules.wordBeforeLength("a -"))
    }

    @Test
    fun `a line break is deleted alone`() {
        assertEquals(1, TextRules.wordBeforeLength("first\n"))
        assertEquals("  ".length, TextRules.wordBeforeLength("first\n  "))
        assertEquals(0, TextRules.wordBeforeLength(""))
        assertEquals(0, TextRules.wordBeforeLength(null))
    }

    @Test
    fun `double space period only after a word`() {
        assertTrue(TextRules.isDoubleSpacePeriod("привет "))
        assertFalse(TextRules.isDoubleSpacePeriod("end. "))
        assertFalse(TextRules.isDoubleSpacePeriod("two  "))
        assertFalse(TextRules.isDoubleSpacePeriod(" "))
    }

    @Test
    fun `shift is applied to words`() {
        assertEquals("привет", TextRules.applyShift("привет", ShiftState.OFF, ru))
        assertEquals("Привет", TextRules.applyShift("привет", ShiftState.ONCE, ru))
        assertEquals("ПРИВЕТ", TextRules.applyShift("привет", ShiftState.LOCKED, ru))
    }

    @Test
    fun `suggestions follow the typed case`() {
        assertEquals("Hello", TextRules.matchCase("hello", "He", Locale.ENGLISH))
        assertEquals("HELLO", TextRules.matchCase("hello", "HE", Locale.ENGLISH))
        assertEquals("hello", TextRules.matchCase("hello", "he", Locale.ENGLISH))
        assertEquals("Hello", TextRules.matchCase("hello", "H", Locale.ENGLISH))
    }

    @Test
    fun `word characters are letters`() {
        assertTrue(TextRules.isWordCharacter("ё"))
        assertTrue(TextRules.isWordCharacter("е\u0301"))
        assertFalse(TextRules.isWordCharacter("\u0301"))
        assertFalse(TextRules.isWordCharacter("1"))
        assertFalse(TextRules.isWordCharacter(","))
    }
}
