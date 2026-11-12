package ru.tinyops.android.vboard.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipExtractorTest {
    private fun values(text: String) = ClipExtractor.extract(text).map { it.kind to it.value }

    @Test
    fun `bare domain inside a sentence is a link`() {
        val text = "я арендовал виртуалку на vast.ai, в итоге за 12 часов управился"
        assertEquals(listOf(ClipKind.LINK to "vast.ai"), values(text))
    }

    @Test
    fun `full url keeps its path and drops trailing punctuation`() {
        assertEquals(
            listOf(ClipKind.LINK to "https://example.com/a/b?x=1"),
            values("Смотри (https://example.com/a/b?x=1)."),
        )
    }

    @Test
    fun `www address is a link`() {
        assertEquals(listOf(ClipKind.LINK to "www.example.org/page"), values("см. www.example.org/page тут"))
    }

    @Test
    fun `email is not split into a link`() {
        assertEquals(listOf(ClipKind.EMAIL to "john.smith@example.com"), values("пиши на john.smith@example.com"))
    }

    @Test
    fun `phone numbers are found with separators`() {
        assertEquals(listOf(ClipKind.PHONE to "+7 (916) 123-45-67"), values("звони +7 (916) 123-45-67 завтра"))
    }

    @Test
    fun `short numbers and dates are not phones`() {
        assertEquals(emptyList(), values("12 часов, 2026-11-05, 1234567"))
    }

    @Test
    fun `abbreviations are not links`() {
        assertEquals(emptyList(), values("т.е. например, и т.д. и e.g. foo"))
    }

    @Test
    fun `duplicates are reported once and order follows the text`() {
        assertEquals(
            listOf(ClipKind.LINK to "a.io", ClipKind.EMAIL to "x@b.com"),
            values("a.io x@b.com a.io"),
        )
    }
}
