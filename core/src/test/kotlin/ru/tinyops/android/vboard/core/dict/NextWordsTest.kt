package ru.tinyops.android.vboard.core.dict

import kotlin.test.Test
import kotlin.test.assertEquals

class NextWordsTest {
    private val dictionary =
        Dictionary.parse(
            """
            доброе	0.0001
            утро	0.0001
            дело	0.0002
            уже	0.01
            утка	0.00001
            и	0.03
            ещё	0.001
            все	0.004
            всё	0.0005
            """.trimIndent().byteInputStream(),
        )

    private val table =
        BigramTable.read(
            bigramTableOf(
                mapOf(
                    ("доброе" to "утро") to 700L,
                    ("доброе" to "дело") to 100L,
                    ("доброе" to "уже") to 2L,
                    ("и" to "еще") to 500L,
                    ("и" to "все") to 300L,
                    ("утро" to "и") to 50L,
                    ("дело" to "и") to 50L,
                    ("уже" to "и") to 1000L,
                    // Makes "уже" as common in the counts as it is in the dictionary.
                    ("дело" to "уже") to 10_000_000L,
                ),
            ),
        )

    private val nextWords = NextWords(table, dictionary)

    @Test
    fun `words seen most often after the previous one come first`() {
        assertEquals(listOf("утро", "дело", "уже"), nextWords.after("доброе", 3))
    }

    @Test
    fun `a folded word gets its dictionary spelling back`() {
        assertEquals(listOf("ещё", "все"), nextWords.after("и", 3))
    }

    @Test
    fun `nothing follows an unknown word or a word with no pairs`() {
        assertEquals(emptyList(), nextWords.after("кот", 3))
        assertEquals(emptyList(), nextWords.after("", 3))
    }

    @Test
    fun `answers are the same when asked again`() {
        assertEquals(nextWords.after("доброе", 3), nextWords.after("доброе", 3))
        assertEquals(listOf("утро"), nextWords.after("доброе", 1))
    }

    @Test
    fun `the previous word lifts a rarer completion above a frequent one`() {
        assertEquals(listOf("утро", "уже", "утка"), nextWords.completions("доброе", "у", 3))
    }

    @Test
    fun `without pairs completions keep the dictionary order`() {
        assertEquals(listOf("уже", "утро", "утка"), nextWords.completions("кот", "у", 3))
        assertEquals(listOf("уже", "утро", "утка"), nextWords.completions("и", "у", 3))
    }

    @Test
    fun `a completion with ё is found by its folded pair`() {
        assertEquals("ещё", nextWords.completions("и", "е", 3).first())
        assertEquals("ещё", nextWords.completions("и", "ещ", 3).first())
    }
}
