package ru.tinyops.android.vboard.core.dict

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * How often words follow each other, counted on Russian Wikipedia by
 * `scripts/swipe_model/bigrams.py`. It tells how much more likely a word becomes after the
 * previous one: `ln P(w | prev) - ln P(w)`, with Dirichlet smoothing toward `P(w)`. The word's own
 * frequency is left out on purpose, since the slide to type model has already learned it.
 *
 * The table carries its own word list (dictionary words, `ё` folded into `е`), so it does not
 * depend on the order of the dictionary: a word missing from either side only means no association.
 *
 * Everything stays in the given buffer, which may be a memory-mapped file of tens of megabytes: a
 * lookup touches a few pages. Only absolute reads are used, so the table is thread-safe.
 *
 * Layout, little-endian, sections aligned to 4 bytes: a header (magic, version, words, pairs as
 * `int`, the number of counted pairs as `long`), word text offsets `int[words + 1]`, the UTF-8 word
 * text in byte order, `ln P(w)` as `float[words]`, the pairs counted after each word `int[words]`,
 * the first pair of each word `int[words + 1]`, and the pairs `int[pairs]`: the id of the second
 * word in the top 19 bits and `round(ln count * 256)` in the low 13, ordered by the second word.
 */
class BigramTable private constructor(
    private val offsets: IntBuffer,
    private val text: ByteBuffer,
    private val unigrams: FloatBuffer,
    private val totals: IntBuffer,
    private val starts: IntBuffer,
    private val entries: IntBuffer,
) {
    val size: Int get() = totals.limit()

    /** The associations of words after [previous] (folded, as [previousWord] gives it), or null if unknown. */
    fun context(previous: String): Context? {
        val id = idOf(previous)
        return if (id < 0) null else Context(id)
    }

    /** Words after one previous word. */
    inner class Context internal constructor(
        private val previous: Int,
    ) {
        private val logTotal = ln(totals.get(previous) + SMOOTHING)

        /** `ln P(word | previous) - ln P(word)` for a folded [word]; the same low value for any unseen pair. */
        fun association(word: String): Double {
            val id = idOf(word)
            val logCount = if (id < 0) Double.NaN else pairLogCount(id)
            if (logCount.isNaN()) return LOG_SMOOTHING - logTotal
            // In the same order of operations as evaluate.py, so the two agree to the last digit.
            return ln(exp(logCount) / exp(unigrams.get(id).toDouble()) + SMOOTHING) - logTotal
        }

        /** The quantized `ln count` of the pair, or NaN when the pair is not in the table. */
        private fun pairLogCount(word: Int): Double {
            var low = starts.get(previous)
            var high = starts.get(previous + 1)
            while (low < high) {
                val middle = (low + high) ushr 1
                val entry = entries.get(middle)
                val entryWord = entry ushr COUNT_BITS
                when {
                    entryWord < word -> low = middle + 1
                    entryWord > word -> high = middle
                    else -> return (entry and COUNT_MASK) / LOG_COUNT_STEP
                }
            }
            return Double.NaN
        }
    }

    private fun idOf(word: String): Int {
        if (word.isEmpty()) return -1
        val key = word.encodeToByteArray()
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            val difference = compareAt(middle, key)
            when {
                difference < 0 -> low = middle + 1
                difference > 0 -> high = middle
                else -> return middle
            }
        }
        return -1
    }

    /** Compares the UTF-8 bytes of word [id] with [key], unsigned, as the file is sorted. */
    private fun compareAt(
        id: Int,
        key: ByteArray,
    ): Int {
        val start = offsets.get(id)
        val length = offsets.get(id + 1) - start
        for (i in 0 until minOf(length, key.size)) {
            val difference = (text.get(start + i).toInt() and BYTE_MASK) - (key[i].toInt() and BYTE_MASK)
            if (difference != 0) return difference
        }
        return length - key.size
    }

    companion object {
        /**
         * Dirichlet smoothing: how many pairs a word must be seen after before its own counts
         * outweigh the plain word frequency. Tuned on the NeuroSwipe validation gestures with
         * previous words from Wikipedia; 3000 to 100000 measure about the same.
         */
        const val SMOOTHING = 30000.0

        private const val MAGIC = 0x4B424247
        private const val VERSION = 1
        private const val WORDS_AT = 8
        private const val PAIRS_AT = 12
        private const val HEADER_BYTES = 24
        private const val MAX_WORDS = 1 shl 19
        private const val COUNT_BITS = 13
        private const val COUNT_MASK = (1 shl COUNT_BITS) - 1
        private const val LOG_COUNT_STEP = 256.0
        private const val BYTE_MASK = 0xFF
        private val LOG_SMOOTHING = ln(SMOOTHING)

        /** Where pairs stop when counting: no pair spans a sentence, a colon, brackets or quotes. */
        private val BREAK = Regex("[.!?…\\n;:()«»\"]")
        private val WHITESPACE = Regex("\\s+")
        private val WORD = Regex("[а-я]+(?:-[а-я]+)*")
        private const val DASHES = "—–-"

        /**
         * The word just before the cursor, folded and lowercased like the counts, or null when the
         * text ends a sentence, ends with something other than a Russian word, or is empty. Commas
         * do not count, as in the counts.
         */
        fun previousWord(precedingText: String): String? {
            val tail = precedingText.takeLast(TAIL_CHARS)
            val sentence =
                tail.substring(
                    BREAK
                        .findAll(tail)
                        .lastOrNull()
                        ?.range
                        ?.last
                        ?.plus(1) ?: 0,
                )
            val token =
                sentence
                    .replace(',', ' ')
                    .split(WHITESPACE)
                    .lastOrNull { it.isNotEmpty() }
                    ?.trim { it in DASHES }
                    ?.lowercase()
                    ?.replace('ё', 'е')
                    ?: return null
            return token.takeIf { WORD.matches(it) }
        }

        private const val TAIL_CHARS = 64

        /** Reads a table written by `bigrams.py build`; the buffer stays in use. */
        fun read(source: ByteBuffer): BigramTable {
            val base = source.slice().order(ByteOrder.LITTLE_ENDIAN)
            require(base.capacity() >= HEADER_BYTES) { "not a bigram table: too short" }
            require(base.getInt(0) == MAGIC) { "not a bigram table: wrong magic" }
            require(base.getInt(INT_BYTES) == VERSION) { "unsupported bigram table version" }
            val words = base.getInt(WORDS_AT)
            val pairs = base.getInt(PAIRS_AT)
            require(words in 0 until MAX_WORDS && pairs >= 0) { "corrupt bigram table header" }

            var at = HEADER_BYTES
            val offsets = base.ints(at, words + 1)
            at += (words + 1) * INT_BYTES
            require(base.capacity() >= at) { "truncated bigram table" }
            val textBytes = offsets.get(words)
            val text = base.bytes(at, textBytes)
            at += (textBytes + INT_BYTES - 1) / INT_BYTES * INT_BYTES
            val unigrams = base.at(at).asFloatBuffer().also { it.limit(words) }
            at += words * INT_BYTES
            val totals = base.ints(at, words)
            at += words * INT_BYTES
            val starts = base.ints(at, words + 1)
            at += (words + 1) * INT_BYTES
            val entries = base.ints(at, pairs)
            at += pairs * INT_BYTES
            require(base.capacity() == at) { "truncated bigram table: ${base.capacity()} bytes, expected $at" }
            return BigramTable(offsets, text, unigrams, totals, starts, entries)
        }

        private const val INT_BYTES = 4

        private fun ByteBuffer.at(position: Int): ByteBuffer {
            require(position <= capacity()) { "truncated bigram table" }
            val view = duplicate().order(ByteOrder.LITTLE_ENDIAN)
            view.position(position)
            return view.slice().order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun ByteBuffer.ints(
            position: Int,
            count: Int,
        ): IntBuffer {
            require(position + count.toLong() * INT_BYTES <= capacity()) { "truncated bigram table" }
            return at(position).asIntBuffer().also { it.limit(count) }
        }

        private fun ByteBuffer.bytes(
            position: Int,
            count: Int,
        ): ByteBuffer {
            require(count >= 0 && position + count.toLong() <= capacity()) { "truncated bigram table" }
            return at(position).also { it.limit(count) }
        }
    }
}
