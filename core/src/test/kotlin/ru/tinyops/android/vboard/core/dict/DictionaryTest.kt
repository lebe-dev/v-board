package ru.tinyops.android.vboard.core.dict

import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.layout.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictionaryTest {
    private val dictionary =
        Dictionary.parse(
            """
            # comment
            hello	100
            help 40
            Hell	20
            hello	5
            he	90
            bad_word	10
            zero	0
            broken
            world	80
            """.trimIndent().byteInputStream(),
        )

    @Test
    fun `parses, lowercases and merges duplicates`() {
        assertEquals(5, dictionary.size)
        assertEquals(105.0, dictionary.frequency("hello"))
        assertTrue(dictionary.contains("hell"))
    }

    @Test
    fun `skips malformed and non-letter entries`() {
        assertFalse(dictionary.contains("bad_word"))
        assertFalse(dictionary.contains("zero"))
        assertNull(dictionary.frequency("broken"))
    }

    @Test
    fun `keeps words whose letters are joined by single hyphens`() {
        val parsed = Dictionary.parse("что-то\t3\nточь-в-точь\t1\n-то\t1\nчто-\t1\nчто--то\t1\n-\t1".byteInputStream())
        assertTrue(parsed.contains("что-то"))
        assertTrue(parsed.contains("точь-в-точь"))
        assertEquals(2, parsed.size)
    }

    @Test
    fun `corrections cover a dropped, added, replaced and swapped letter`() {
        assertEquals(listOf("hello"), dictionary.corrections("helo", 3).take(1))
        assertEquals(listOf("hello"), dictionary.corrections("hellop", 3))
        assertEquals(listOf("hello"), dictionary.corrections("hallo", 3).take(1))
        assertEquals(listOf("hello"), dictionary.corrections("hlelo", 3))
    }

    @Test
    fun `corrections are ranked by frequency and limited`() {
        assertEquals(listOf("help", "hell"), dictionary.corrections("helq", 3))
        assertEquals(listOf("help"), dictionary.corrections("helq", 1))
    }

    @Test
    fun `a known word needs no corrections`() {
        assertTrue(dictionary.corrections("hello", 3).isEmpty())
    }

    @Test
    fun `corrections of a russian word use russian letters`() {
        val russian = Dictionary.parse("привет\t10\nпривод\t5".byteInputStream())
        assertEquals(listOf("привет"), russian.corrections("превет", 3))
    }

    @Test
    fun `completions are ranked by frequency`() {
        assertEquals(listOf("hello", "he", "help"), dictionary.completions("he", 3))
        assertEquals(listOf("hello", "help", "hell"), dictionary.completions("hel", 5))
    }

    @Test
    fun `completions for an unknown prefix are empty`() {
        assertTrue(dictionary.completions("xyz", 3).isEmpty())
        assertTrue(dictionary.completions("", 3).isEmpty())
    }

    @Test
    fun `equally frequent completions keep alphabetical order`() {
        val tied = Dictionary.parse("cc\t5\nca\t5\ncb\t5\ncd\t9".byteInputStream())
        assertEquals(listOf("cd", "ca", "cb"), tied.completions("c", 3))
        assertEquals(listOf("cd", "ca", "cb", "cc"), tied.completions("c", 10))
        assertEquals(listOf("cd"), tied.completions("c", 1))
    }

    @Test
    fun `accepts any run of separators and rejects extra columns`() {
        val parsed =
            Dictionary.parse(
                "  spaced \t \t 7  \ntabbed\t\t8\nthree cols 9\nnumber\tabc\n#hash\t1\n\n".byteInputStream(),
            )
        assertEquals(7.0, parsed.frequency("spaced"))
        assertEquals(8.0, parsed.frequency("tabbed"))
        assertEquals(2, parsed.size)
    }

    @Test
    fun `bundled dictionaries load`() {
        for (language in Language.entries) {
            val bundled = bundledDictionary(language)
            assertTrue(bundled.size > 20_000, "$language size=${bundled.size}")
            assertEquals(bundled.size, bundled.languageModel.vocabularySize)
        }
        assertTrue(bundledDictionary(Language.RUSSIAN).contains("привет"))
        assertTrue(bundledDictionary(Language.RUSSIAN).contains("что-то"))
        assertTrue(bundledDictionary(Language.RUSSIAN).contains("из-за"))
        assertTrue(bundledDictionary(Language.ENGLISH).contains("keyboard"))
    }
}
