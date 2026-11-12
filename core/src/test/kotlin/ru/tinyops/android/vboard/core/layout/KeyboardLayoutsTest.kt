package ru.tinyops.android.vboard.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyboardLayoutsTest {
    @Test
    fun `every page has rows of equal width`() {
        for (language in Language.entries) {
            for (mode in KeyboardMode.entries) {
                val spec = KeyboardLayouts.spec(language, mode)
                val widths = spec.rows.map { it.totalWidth }.distinct()
                assertEquals(1, widths.size, "$language $mode widths=$widths")
            }
        }
    }

    @Test
    fun `english letters cover the latin alphabet once`() {
        assertEquals(('a'..'z').joinToString(""), letters(Language.ENGLISH).sorted().joinToString(""))
    }

    @Test
    fun `russian letters cover the alphabet except yo and hard sign`() {
        val expected = "абвгдежзийклмнопрстуфхцчшщыьэюя"
        assertEquals(expected, letters(Language.RUSSIAN).sorted().joinToString(""))
    }

    @Test
    fun `yo and hard sign are long-press alternates`() {
        val keys = KeyboardLayouts.spec(Language.RUSSIAN, KeyboardMode.LETTERS).rows.flatMap { it.keys }
        assertEquals("ё", keys.first { it.output == "е" }.alternates.first())
        assertEquals(listOf("ъ"), keys.first { it.output == "ь" }.alternates)
    }

    @Test
    fun `russian vowels offer a stressed variant`() {
        val keys = KeyboardLayouts.spec(Language.RUSSIAN, KeyboardMode.LETTERS).rows.flatMap { it.keys }
        for (vowel in "аеиоуыэюя") {
            val key = keys.first { it.output == vowel.toString() }
            assertTrue("$vowel\u0301" in key.alternates, "no stressed variant for $vowel")
        }
        assertEquals(listOf("ё", "5", "е\u0301"), keys.first { it.output == "е" }.alternates)
    }

    @Test
    fun `comma offers the emoji picker first and then punctuation`() {
        for (language in Language.entries) {
            val bottom =
                KeyboardLayouts
                    .spec(language, KeyboardMode.LETTERS)
                    .rows
                    .last()
                    .keys
            val comma = bottom.first { it.output == "," }
            assertEquals(KeySpec.EMOJI_ALTERNATE, comma.alternates.first(), "$language")
            assertTrue(comma.showsEmojiHint, "$language")
            assertEquals(comma.alternates.distinct(), comma.alternates, "$language has duplicates")
            for (mark in listOf(",", "&", "%", "+", "\"", "-", ":", "'", "@", ";", "/", "(", ")", "#", "!", "?")) {
                assertTrue(mark in comma.alternates, "$language comma lacks $mark")
            }
        }
    }

    @Test
    fun `every page has exactly one key that opens the emoji picker`() {
        for (language in Language.entries) {
            for (mode in KeyboardMode.entries) {
                val keys = KeyboardLayouts.spec(language, mode).rows.flatMap { it.keys }
                val withEmoji = keys.filter { KeySpec.EMOJI_ALTERNATE in it.alternates }
                assertEquals(1, withEmoji.size, "$language $mode")
            }
        }
    }

    @Test
    fun `top letter row carries digit hints`() {
        for (language in Language.entries) {
            val top =
                KeyboardLayouts
                    .spec(language, KeyboardMode.LETTERS)
                    .rows
                    .first()
                    .keys
            assertEquals("1234567890", top.take(10).joinToString("") { it.hint.orEmpty() })
            assertTrue(top.take(10).all { it.hint in it.alternates })
        }
    }

    @Test
    fun `double quote offers guillemets and typographic quotes`() {
        val russian = symbolKey(Language.RUSSIAN, KeyboardMode.SYMBOLS, "\"")
        assertEquals(listOf("«", "»", "„", "“", "”"), russian.alternates)
        val english = symbolKey(Language.ENGLISH, KeyboardMode.SYMBOLS, "\"")
        assertEquals(listOf("“", "”", "„", "«", "»"), english.alternates)
    }

    @Test
    fun `apostrophe offers single typographic quotes`() {
        val key = symbolKey(Language.ENGLISH, KeyboardMode.SYMBOLS, "'")
        assertEquals(listOf("‘", "’", "‚", "‹", "›"), key.alternates)
    }

    @Test
    fun `dash offers longer dashes`() {
        val key = symbolKey(Language.RUSSIAN, KeyboardMode.SYMBOLS, "-")
        assertEquals(listOf("–", "—"), key.alternates.take(2))
    }

    @Test
    fun `currency key offers the other currencies`() {
        val dollar = symbolKey(Language.ENGLISH, KeyboardMode.SYMBOLS, "$")
        assertTrue("€" in dollar.alternates && "£" in dollar.alternates && "$" !in dollar.alternates)
        val ruble = symbolKey(Language.RUSSIAN, KeyboardMode.SYMBOLS, "₽")
        assertTrue("$" in ruble.alternates && "€" in ruble.alternates && "₽" !in ruble.alternates)
    }

    @Test
    fun `inverted marks and number sign are alternates`() {
        assertEquals(listOf("¡"), symbolKey(Language.ENGLISH, KeyboardMode.SYMBOLS, "!").alternates)
        assertEquals(listOf("¿"), symbolKey(Language.ENGLISH, KeyboardMode.SYMBOLS, "?").alternates)
        assertEquals("№", symbolKey(Language.RUSSIAN, KeyboardMode.SYMBOLS, "#").alternates.first())
    }

    @Test
    fun `symbol alternates are sane on every page`() {
        for (language in Language.entries) {
            for (mode in listOf(KeyboardMode.SYMBOLS, KeyboardMode.MORE_SYMBOLS)) {
                val keys =
                    KeyboardLayouts
                        .spec(language, mode)
                        .rows
                        .dropLast(1)
                        .flatMap { it.keys }
                for (key in keys.filter { it.type == KeyType.CHARACTER }) {
                    val alternates = key.alternates
                    val where = "$language $mode '${key.output}'"
                    assertEquals(alternates.distinct(), alternates, "$where has duplicates")
                    assertTrue(key.output !in alternates, "$where offers itself")
                    assertTrue(alternates.size <= 8, "$where has too many: $alternates")
                    assertTrue(alternates.all { it.isNotEmpty() }, "$where has an empty alternate")
                }
            }
        }
    }

    @Test
    fun `russian shift and backspace are wider than the letters beside them`() {
        val row = KeyboardLayouts.spec(Language.RUSSIAN, KeyboardMode.LETTERS).rows[2].keys
        val letter = row.first { it.isLetter }.width
        assertTrue(row.first().type == KeyType.SHIFT && row.first().width > letter * 1.2f)
        assertTrue(row.last().type == KeyType.BACKSPACE && row.last().width > letter * 1.2f)
    }

    @Test
    fun `bottom row keys around the space bar are wider than a letter`() {
        for (language in Language.entries) {
            val bottom =
                KeyboardLayouts
                    .spec(language, KeyboardMode.LETTERS)
                    .rows
                    .last()
                    .keys
            val small = bottom.filter { it.type == KeyType.CHARACTER || it.type == KeyType.LANGUAGE }
            assertTrue(small.all { it.width >= 1f }, "$language bottom row: ${small.map { it.width }}")
            assertTrue(bottom.first().width >= 1.5f && bottom.last().width >= 1.5f)
        }
    }

    @Test
    fun `every page has the navigation keys`() {
        for (language in Language.entries) {
            for (mode in KeyboardMode.entries) {
                val types = KeyboardLayouts.spec(language, mode).rows.flatMap { row -> row.keys.map { it.type } }
                for (required in listOf(KeyType.BACKSPACE, KeyType.SPACE, KeyType.ENTER, KeyType.LANGUAGE)) {
                    assertTrue(required in types, "$language $mode lacks $required")
                }
            }
        }
    }

    private fun letters(language: Language): List<Char> =
        KeyboardLayouts
            .spec(language, KeyboardMode.LETTERS)
            .rows
            .flatMap { it.keys }
            .filter { it.isLetter }
            .map { it.output.single() }

    private fun symbolKey(
        language: Language,
        mode: KeyboardMode,
        output: String,
    ): KeySpec =
        KeyboardLayouts
            .spec(language, mode)
            .rows
            .flatMap { it.keys }
            .first { it.output == output }
}
