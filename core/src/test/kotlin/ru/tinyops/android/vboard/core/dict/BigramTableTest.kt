package ru.tinyops.android.vboard.core.dict

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Writes a table the way `scripts/swipe_model/bigrams.py build` does, for small known counts. */
internal fun bigramTableOf(
    pairs: Map<Pair<String, String>, Long>,
    totals: Map<String, Long> = emptyMap(),
    version: Int = 1,
): ByteBuffer {
    val words =
        pairs.keys
            .flatMap { listOf(it.first, it.second) }
            .toSortedSet(compareBy(Utf8Order) { it })
            .toList()
    val ids = words.withIndex().associate { it.value to it.index }
    val totalPairs = pairs.values.sum()
    val unigrams = LongArray(words.size)
    val sums = LongArray(words.size)
    for ((pair, count) in pairs) {
        unigrams[ids.getValue(pair.second)] += count
        sums[ids.getValue(pair.first)] += count
    }
    for ((word, total) in totals) sums[ids.getValue(word)] = total
    val seen = unigrams.count { it > 0 }
    val sorted = pairs.entries.sortedWith(compareBy({ ids[it.key.first] }, { ids[it.key.second] }))
    val text = words.map { it.encodeToByteArray() }
    val textBytes = text.sumOf { it.size }

    val output = ByteArrayOutputStream()
    val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    header
        .putInt(0x4B424247)
        .putInt(version)
        .putInt(words.size)
        .putInt(pairs.size)
        .putLong(totalPairs)
    output.write(header.array())

    fun ints(values: List<Int>) {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putInt)
        output.write(buffer.array())
    }
    ints(text.runningFold(0) { at, word -> at + word.size })
    text.forEach(output::write)
    output.write(ByteArray((4 - textBytes % 4) % 4))
    ints(unigrams.map { java.lang.Float.floatToIntBits(ln((it + 0.5) / (totalPairs + 0.5 * seen)).toFloat()) })
    ints(sums.map { it.toInt() })
    ints(words.indices.runningFold(0) { at, id -> at + sorted.count { ids[it.key.first] == id } })
    ints(sorted.map { (ids.getValue(it.key.second) shl 13) or (ln(it.value.toDouble()) * 256).roundToInt() })
    return ByteBuffer.wrap(output.toByteArray())
}

private object Utf8Order : Comparator<String> {
    override fun compare(
        a: String,
        b: String,
    ): Int {
        val x = a.encodeToByteArray()
        val y = b.encodeToByteArray()
        for (i in 0 until minOf(x.size, y.size)) {
            val difference = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (difference != 0) return difference
        }
        return x.size - y.size
    }
}

class BigramTableTest {
    private val pairs =
        mapOf(
            ("я" to "буду") to 40L,
            ("я" to "будем") to 1L,
            ("мы" to "будем") to 30L,
            ("мы" to "буду") to 1L,
            ("мы" to "дома") to 8L,
        )

    private val table = BigramTable.read(bigramTableOf(pairs))

    /** `log P(w | prev) - log P(w)` with the smoothing of the table, from the raw counts. */
    private fun expected(
        pair: Long,
        total: Long,
        unigram: Long,
    ): Double {
        val all = pairs.values.sum()
        val probability = (unigram + 0.5) / (all + 0.5 * 3)
        val smoothing = BigramTable.SMOOTHING
        return ln((pair + smoothing * probability) / (total + smoothing)) - ln(probability)
    }

    @Test
    fun `a pair seen together is associated more than the word on its own`() {
        val context = assertNotNull(table.context("я"))
        assertEquals(expected(pair = 40, total = 41, unigram = 41), context.association("буду"), 1e-3)
        assertEquals(expected(pair = 1, total = 41, unigram = 31), context.association("будем"), 1e-3)
        assert(context.association("буду") > 0.0)
        assert(context.association("будем") < context.association("буду"))
    }

    @Test
    fun `the previous word decides which ending wins`() {
        val me = assertNotNull(table.context("я"))
        val we = assertNotNull(table.context("мы"))
        assert(me.association("буду") > me.association("будем"))
        assert(we.association("будем") > we.association("буду"))
    }

    @Test
    fun `a word never seen after the previous one gets the penalty of an unseen pair`() {
        val context = assertNotNull(table.context("я"))
        val unseen = ln(BigramTable.SMOOTHING) - ln(41 + BigramTable.SMOOTHING)
        assertEquals(unseen, context.association("дома"), 1e-9)
        assertEquals(unseen, context.association("кот"), 1e-9)
    }

    @Test
    fun `the total of the previous word counts pairs the table left out`() {
        val table = BigramTable.read(bigramTableOf(pairs, totals = mapOf("я" to 1000L)))
        val context = assertNotNull(table.context("я"))
        assertEquals(ln(BigramTable.SMOOTHING) - ln(1000 + BigramTable.SMOOTHING), context.association("кот"), 1e-9)
    }

    @Test
    fun `a previous word the table does not know gives no context`() {
        assertNull(table.context("кот"))
        assertNull(table.context(""))
    }

    @Test
    fun `an empty table gives no context`() {
        val empty = BigramTable.read(bigramTableOf(emptyMap()))
        assertNull(empty.context("я"))
    }

    @Test
    fun `a file of another format is refused`() {
        assertFailsWith<IllegalArgumentException> { BigramTable.read(ByteBuffer.wrap(ByteArray(10))) }
        assertFailsWith<IllegalArgumentException> { BigramTable.read(ByteBuffer.wrap(ByteArray(64))) }
        assertFailsWith<IllegalArgumentException> { BigramTable.read(bigramTableOf(pairs, version = 2)) }
        val whole = bigramTableOf(pairs)
        val truncated = ByteBuffer.wrap(whole.array().copyOf(whole.capacity() - 4))
        assertFailsWith<IllegalArgumentException> { BigramTable.read(truncated) }
    }

    @Test
    fun `counts survive the quantization closely`() {
        val big = 1_234_567L
        val table = BigramTable.read(bigramTableOf(mapOf(("а" to "б") to big)))
        val context = assertNotNull(table.context("а"))
        val probability = (big + 0.5) / (big + 0.5)
        val exact = ln((big + BigramTable.SMOOTHING * probability) / (big + BigramTable.SMOOTHING)) - ln(probability)
        assertEquals(exact, context.association("б"), 1e-2)
    }

    @Test
    fun `the previous word is the last one before the cursor, folded and lowercased`() {
        assertEquals("мы", BigramTable.previousWord("Завтра Мы "))
        assertEquals("мы", BigramTable.previousWord("завтра мы"))
        assertEquals("еще", BigramTable.previousWord("и ЕЩЁ "))
        assertEquals("что-то", BigramTable.previousWord("сказал что-то "))
        assertEquals("мы", BigramTable.previousWord("да, мы, "))
    }

    @Test
    fun `there is no previous word across the end of a sentence or other breaks`() {
        assertNull(BigramTable.previousWord(""))
        assertNull(BigramTable.previousWord("   "))
        assertNull(BigramTable.previousWord("Пришли. "))
        assertNull(BigramTable.previousWord("Пришли!"))
        assertNull(BigramTable.previousWord("так…"))
        assertNull(BigramTable.previousWord("вот: "))
        assertNull(BigramTable.previousWord("(скобка) "))
        assertNull(BigramTable.previousWord("строка\n"))
        assertNull(BigramTable.previousWord("«цитата» "))
        assertNull(BigramTable.previousWord("и мы — "), "a lone dash breaks the pair, as in the counts")
    }

    @Test
    fun `a token that is not a Russian word gives no previous word`() {
        assertNull(BigramTable.previousWord("в 2024 "))
        assertNull(BigramTable.previousWord("через hello "))
        assertNull(BigramTable.previousWord("слово2 "))
        assertNull(BigramTable.previousWord("он сказал@ "))
    }
}
