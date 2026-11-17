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
    fun `facebook click identifier is removed`() {
        assertEquals(
            "https://platipomiru.com/",
            LinkCleaner.clean(
                "https://platipomiru.com/?clckid=8fb0dfd5&fbclid=PAT01DUAVpOHVleHRuA2FlbQIxMABwZG9mAnNydGMGYXBwX2lk" +
                    "DzU2NzA2NzM0MzM1MjQyNwABp_cGldgS8Odh4wxF39MxjLL5JDi87AI85n-jPNvuQajkDasPHuh9REbK2DsL" +
                    "_aem_2aPGMFzguTeD7pNBJYgXMQ",
            ),
        )
    }

    @Test
    fun `yandex click identifier is removed`() {
        assertEquals(
            "https://example.com/item?id=42",
            LinkCleaner.clean("https://example.com/item?clckid=a&yclid=b&id=42"),
        )
    }

    @Test
    fun `microsoft click identifier is removed`() {
        assertEquals(
            "https://example.com/item?id=42",
            LinkCleaner.clean("https://example.com/item?msclkid=a&id=42"),
        )
    }

    @Test
    fun `instagram share and meta pixel identifiers are removed`() {
        assertEquals(
            "https://example.com/p/1?x=1",
            LinkCleaner.clean("https://example.com/p/1?igsh=a&igshid=b&_fbp=c&x=1"),
        )
    }

    @Test
    fun `parameter names are matched without regard to case`() {
        assertEquals("https://example.com/a", LinkCleaner.clean("https://example.com/a?UTM_Source=x&GCLID=y&FbClId=z"))
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
        val text = "https://example.com/a?utm=1&my_utm_source=2&gclid_note=3&x_fbclid=4&igshid_note=5"
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
