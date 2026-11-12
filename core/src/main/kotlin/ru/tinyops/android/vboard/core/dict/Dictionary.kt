package ru.tinyops.android.vboard.core.dict

import ru.tinyops.android.vboard.stt.lm.FrequencyLanguageModel
import ru.tinyops.android.vboard.stt.lm.LanguageModel
import ru.tinyops.android.vboard.stt.lm.WordSource
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.IntBuffer

/**
 * A word list with frequencies. Serves prefix completions for tap typing and the language
 * model that constrains gesture decoding.
 */
class Dictionary internal constructor(
    internal val words: WordTable,
    internal val frequencies: DoubleArray,
    model: () -> LanguageModel,
) {
    val size: Int get() = words.size

    /** Unigram model over the same vocabulary, used by the gesture decoder. */
    val languageModel: LanguageModel by lazy(LazyThreadSafetyMode.SYNCHRONIZED, model)

    fun contains(word: String): Boolean = indexOf(word) >= 0

    fun frequency(word: String): Double? = indexOf(word).takeIf { it >= 0 }?.let { frequencies[it] }

    /**
     * The [limit] most frequent words starting with [prefix] (lowercase), most frequent first.
     * The prefix itself is included when it is a word. Runs on the main thread for every key
     * press and a one-letter prefix matches thousands of words, so instead of sorting them the
     * top [limit] are selected in place.
     */
    fun completions(
        prefix: String,
        limit: Int,
    ): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val from = lowerBound(prefix)
        val to = lowerBound(prefix + Char.MAX_VALUE)
        if (from >= to) return emptyList()
        val best = IntArray(minOf(limit, to - from))
        var count = 0
        for (index in from until to) {
            val frequency = frequencies[index]
            if (count == best.size && frequency <= frequencies[best[count - 1]]) continue
            var slot = if (count < best.size) count++ else count - 1
            // Strict comparison: equally frequent words keep their alphabetical order.
            while (slot > 0 && frequencies[best[slot - 1]] < frequency) {
                best[slot] = best[slot - 1]
                slot--
            }
            best[slot] = index
        }
        return List(count) { words.wordAt(best[it]) }
    }

    /**
     * Up to [limit] dictionary words one edit away from [word] (lowercase): a letter dropped, added, replaced or
     * swapped with its neighbour, most frequent first. Empty when [word] is a word itself, as there is nothing to
     * correct. The edits are looked up one by one, so this is meant for a single word, not for every key press.
     */
    fun corrections(
        word: String,
        limit: Int,
    ): List<String> {
        if (word.isEmpty() || limit <= 0 || contains(word)) return emptyList()
        val alphabet = alphabetOf(word)
        val found = HashSet<String>()

        fun consider(candidate: String) {
            if (candidate.isNotEmpty() && candidate != word && contains(candidate)) found += candidate
        }
        for (i in word.indices) {
            consider(word.removeRange(i, i + 1))
            if (i + 1 < word.length) {
                consider(word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2))
            }
            for (letter in alphabet) {
                consider(word.substring(0, i) + letter + word.substring(i + 1))
                consider(word.substring(0, i) + letter + word.substring(i))
            }
        }
        for (letter in alphabet) consider(word + letter)
        return found.sortedWith(compareByDescending<String> { frequency(it) ?: 0.0 }.thenBy { it }).take(limit)
    }

    private fun alphabetOf(word: String): String = if (word.any { it in CYRILLIC }) RUSSIAN_LETTERS else LATIN_LETTERS

    private fun indexOf(word: String): Int {
        val index = lowerBound(word)
        return if (index < words.size && words.compareAt(index, word) == 0) index else -1
    }

    /** First index whose word is `>= key`. */
    private fun lowerBound(key: String): Int {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words.compareAt(mid, key) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        private const val RUSSIAN_LETTERS = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
        private const val LATIN_LETTERS = "abcdefghijklmnopqrstuvwxyz"
        private val CYRILLIC = 'а'..'я'

        /**
         * Parses `word<TAB>frequency` lines. Blank lines, `#` comments and malformed lines are
         * skipped; words are lowercased and duplicate frequencies are summed.
         */
        fun parse(input: InputStream): Dictionary {
            val merged = HashMap<String, Double>()
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val entry = parseLine(line) ?: continue
                    merged.merge(entry.first, entry.second, Double::plus)
                }
            }
            val sorted = merged.entries.sortedBy { it.key }
            val words = Array(sorted.size) { sorted[it].key }
            val frequencies = DoubleArray(sorted.size) { sorted[it].value }
            return Dictionary(WordTable.of(words), frequencies) { FrequencyLanguageModel.fromWords(words, frequencies) }
        }

        /** Restores a dictionary from an image made by [DictionaryImage], without parsing or sorting anything. */
        fun read(image: ByteBuffer): Dictionary = DictionaryImage.read(image)

        private fun parseLine(line: String): Pair<String, Double>? {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed[0] == '#') return null
            val wordEnd = trimmed.indexOfFirst(::isSeparator)
            if (wordEnd < 0) return null
            val frequencyStart = trimmed.indexOfLast(::isSeparator) + 1
            for (i in wordEnd until frequencyStart) if (!isSeparator(trimmed[i])) return null
            val frequency = trimmed.substring(frequencyStart).toDoubleOrNull() ?: return null
            val word = trimmed.substring(0, wordEnd).lowercase()
            if (frequency <= 0.0 || !isWord(word)) return null
            return word to frequency
        }

        /** Letters, in parts joined by single hyphens: `что-то`, `точь-в-точь`. */
        private fun isWord(word: String): Boolean =
            word.split('-').all { part -> part.isNotEmpty() && part.all { it.isLetter() } }

        private fun isSeparator(c: Char): Boolean = c == '\t' || c == ' '
    }
}

/**
 * Sorted words as one block of text: no object per word for the collector to walk, and the block
 * may stay in a memory-mapped file. Only absolute reads are used, so the table is thread-safe.
 */
internal class WordTable(
    private val offsets: IntBuffer,
    private val text: CharBuffer,
) : WordSource {
    val size: Int get() = offsets.limit() - 1

    override fun wordAt(index: Int): String {
        val start = offsets.get(index)
        return String(CharArray(offsets.get(index + 1) - start) { text.get(start + it) })
    }

    /** Compares the word at [index] with [key], in the order of [String.compareTo]. */
    fun compareAt(
        index: Int,
        key: String,
    ): Int {
        val start = offsets.get(index)
        val length = offsets.get(index + 1) - start
        for (i in 0 until minOf(length, key.length)) {
            val difference = text.get(start + i) - key[i]
            if (difference != 0) return difference
        }
        return length - key.length
    }

    companion object {
        fun of(words: Array<String>): WordTable {
            val offsets = IntArray(words.size + 1)
            for (i in words.indices) offsets[i + 1] = offsets[i] + words[i].length
            val text = CharArray(offsets[words.size])
            for (i in words.indices) words[i].toCharArray(text, offsets[i])
            return WordTable(IntBuffer.wrap(offsets), CharBuffer.wrap(text))
        }
    }
}
