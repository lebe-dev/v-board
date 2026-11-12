package ru.tinyops.android.vboard.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ParseCacheTest {
    private var parses = 0
    private val cache =
        ParseCache { text: String? ->
            parses++
            text?.split(',').orEmpty()
        }

    @Test
    fun `the same text is parsed once`() {
        val first = cache("a,b")
        val second = cache("a,b")
        assertSame(first, second)
        assertEquals(1, parses)
    }

    @Test
    fun `an equal text in another string is not parsed again`() {
        cache(StringBuilder("a,b").toString())
        cache(StringBuilder("a,b").toString())
        assertEquals(1, parses)
    }

    @Test
    fun `a changed text is parsed again, and so is the old one after it`() {
        assertEquals(listOf("a", "b"), cache("a,b"))
        assertEquals(listOf("c"), cache("c"))
        assertEquals(listOf("a", "b"), cache("a,b"))
        assertEquals(3, parses)
    }

    @Test
    fun `a missing value is remembered too`() {
        assertEquals(emptyList(), cache(null))
        assertEquals(emptyList(), cache(null))
        assertEquals(1, parses)
        assertEquals(listOf("x"), cache("x"))
        assertEquals(2, parses)
    }
}
