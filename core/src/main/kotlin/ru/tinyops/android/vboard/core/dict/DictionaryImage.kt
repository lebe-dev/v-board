package ru.tinyops.android.vboard.core.dict

import ru.tinyops.android.vboard.stt.lm.FrequencyLanguageModel
import ru.tinyops.android.vboard.stt.lm.TrieArrays
import ru.tinyops.android.vboard.stt.lm.WordTrie
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The binary form of a [Dictionary], generated from the word list at build time so that the
 * keyboard does not parse, sort and index the list every time its process starts.
 *
 * Little-endian: a fixed header, then sections aligned to 8 bytes in the order [Layout] lists
 * them. Numbers used on hot paths are copied into primitive arrays; the word text and its offsets
 * stay in the given buffer, which may be a memory-mapped file.
 */
internal object DictionaryImage {
    private const val MAGIC = 0x4B424443
    private const val VERSION = 1
    private const val VERSION_AT = 4
    private const val WORDS_AT = 8
    private const val NODES_AT = 12
    private const val TEXT_CHARS_AT = 16
    private const val UNKNOWN_LOG_PROB_AT = 24
    private const val HEADER_BYTES = 32
    private const val SECTION_ALIGNMENT = 8

    private class Layout(
        val words: Int,
        val nodes: Int,
        val textChars: Int,
    ) {
        private var end = HEADER_BYTES

        val frequencies = section(words * Double.SIZE_BYTES)
        val logProbs = section(words * Double.SIZE_BYTES)
        val firstChild = section(nodes * Int.SIZE_BYTES)
        val nextSibling = section(nodes * Int.SIZE_BYTES)
        val wordIndex = section(nodes * Int.SIZE_BYTES)
        val labels = section(nodes * Char.SIZE_BYTES)
        val offsets = section((words + 1) * Int.SIZE_BYTES)
        val text = section(textChars * Char.SIZE_BYTES)
        val totalBytes = end

        private fun section(bytes: Int): Int {
            val start = end
            end = (start + bytes + SECTION_ALIGNMENT - 1) / SECTION_ALIGNMENT * SECTION_ALIGNMENT
            return start
        }
    }

    fun write(
        dictionary: Dictionary,
        output: OutputStream,
    ) {
        val model = dictionary.languageModel
        val trie = model.trie.toArrays()
        val words = dictionary.words
        val texts = Array(words.size, words::wordAt)
        val layout = Layout(words.size, trie.labels.size, texts.sumOf { it.length })

        val image = ByteBuffer.allocate(layout.totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        image.putInt(0, MAGIC).putInt(VERSION_AT, VERSION)
        image.putInt(WORDS_AT, layout.words).putInt(NODES_AT, layout.nodes).putInt(TEXT_CHARS_AT, layout.textChars)
        image.putDouble(UNKNOWN_LOG_PROB_AT, model.unknownLogProb)

        image.at(layout.frequencies).asDoubleBuffer().put(dictionary.frequencies)
        image.at(layout.logProbs).asDoubleBuffer().put(trie.logProbs)
        image.at(layout.firstChild).asIntBuffer().put(trie.firstChild)
        image.at(layout.nextSibling).asIntBuffer().put(trie.nextSibling)
        image.at(layout.wordIndex).asIntBuffer().put(trie.wordIndex)
        image.at(layout.labels).asCharBuffer().put(trie.labels)
        val offsets = image.at(layout.offsets).asIntBuffer()
        val text = image.at(layout.text).asCharBuffer()
        var position = 0
        offsets.put(position)
        for (word in texts) {
            text.put(word)
            position += word.length
            offsets.put(position)
        }
        output.write(image.array())
    }

    fun read(source: ByteBuffer): Dictionary {
        val image = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val start = image.position()
        require(image.remaining() >= HEADER_BYTES) { "not a dictionary image: too short" }
        require(image.getInt(start) == MAGIC) { "not a dictionary image: wrong magic" }
        require(image.getInt(start + VERSION_AT) == VERSION) { "unsupported dictionary image version" }
        val base = image.slice().order(ByteOrder.LITTLE_ENDIAN)
        val layout =
            Layout(
                words = base.getInt(WORDS_AT),
                nodes = base.getInt(NODES_AT),
                textChars = base.getInt(TEXT_CHARS_AT),
            )
        require(base.capacity() == layout.totalBytes) {
            "truncated dictionary image: ${base.capacity()} bytes, expected ${layout.totalBytes}"
        }

        val frequencies = DoubleArray(layout.words).also { base.at(layout.frequencies).asDoubleBuffer().get(it) }
        val trieArrays =
            TrieArrays(
                labels = CharArray(layout.nodes).also { base.at(layout.labels).asCharBuffer().get(it) },
                firstChild = base.ints(layout.firstChild, layout.nodes),
                nextSibling = base.ints(layout.nextSibling, layout.nodes),
                wordIndex = base.ints(layout.wordIndex, layout.nodes),
                logProbs = DoubleArray(layout.words).also { base.at(layout.logProbs).asDoubleBuffer().get(it) },
                frequencies = frequencies,
            )
        val words =
            WordTable(
                offsets = base.at(layout.offsets).asIntBuffer().also { it.limit(layout.words + 1) },
                text = base.at(layout.text).asCharBuffer().also { it.limit(layout.textChars) },
            )
        val unknownLogProb = base.getDouble(UNKNOWN_LOG_PROB_AT)
        val model = FrequencyLanguageModel.fromTrie(WordTrie.fromArrays(trieArrays, words), unknownLogProb)
        return Dictionary(words, frequencies) { model }
    }

    private fun ByteBuffer.at(position: Int): ByteBuffer {
        val view = duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(position)
        return view.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun ByteBuffer.ints(
        position: Int,
        count: Int,
    ): IntArray = IntArray(count).also { at(position).asIntBuffer().get(it) }
}

/** Build tool: turns every `<lang>.tsv` of the first argument into `<second argument>/<lang>.dict`. */
fun main(args: Array<String>) {
    val (sourceDir, outputDir) = args.map(::File)
    outputDir.mkdirs()
    for (source in sourceDir.listFiles { file -> file.extension == "tsv" }.orEmpty()) {
        val dictionary = source.inputStream().use(Dictionary::parse)
        val target = outputDir.resolve("${source.nameWithoutExtension}.dict")
        target.outputStream().buffered().use { DictionaryImage.write(dictionary, it) }
        println("${source.name}: ${dictionary.size} words -> ${target.name}, ${target.length()} bytes")
    }
}
