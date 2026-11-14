package ru.tinyops.android.vboard.core.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LinkCleanerTest {
    @Test
    fun `utm parameters are removed and the rest of the query stays`() {
        assertEquals(
            "https://shop.example/item?id=42&color=red",
            LinkCleaner.clean("https://shop.example/item?utm_source=google&id=42&utm_medium=cpc&color=red&utm_term=x"),
        )
    }

    @Test
    fun `query made only of tracking parameters goes away with its question mark`() {
        assertEquals(
            "https://example.com/page",
            LinkCleaner.clean("https://example.com/page?utm_source=a&utm_campaign=b&gclid=XYZ&gad_source=1"),
        )
    }

    @Test
    fun `google click identifiers are removed`() {
        assertEquals(
            "https://example.com/?q=1",
            LinkCleaner.clean("https://example.com/?gclid=a&q=1&gbraid=b&wbraid=c&dclid=d&gclsrc=aw.ds&srsltid=e"),
        )
    }

    @Test
    fun `parameter names are matched without regard to case`() {
        assertEquals("https://example.com/a", LinkCleaner.clean("https://example.com/a?UTM_Source=x&GCLID=y"))
    }

    @Test
    fun `fragment is kept`() {
        assertEquals(
            "https://example.com/a?x=1#part",
            LinkCleaner.clean("https://example.com/a?x=1&utm_source=y#part"),
        )
    }

    @Test
    fun `links inside text are cleaned and trailing punctuation stays outside`() {
        assertEquals(
            "Смотри https://example.com/a, и ещё (www.example.org/b?k=v).",
            LinkCleaner.clean(
                "Смотри https://example.com/a?utm_source=tg, и ещё (www.example.org/b?k=v&utm_medium=x).",
            ),
        )
    }

    @Test
    fun `parameters that only look similar are kept`() {
        val text = "https://example.com/a?utm=1&my_utm_source=2&gclid_note=3"
        assertSame(text, LinkCleaner.clean(text))
    }

    @Test
    fun `text without tracking parameters is returned as is`() {
        val text = "Привет, вот ссылка https://example.com/a?x=1 и всё"
        assertSame(text, LinkCleaner.clean(text))
    }

    @Test
    fun `empty parameters and duplicated ampersands do not break the link`() {
        assertEquals("https://example.com/a?x=1", LinkCleaner.clean("https://example.com/a?utm_source=&&x=1"))
    }
}
