package ru.tinyops.android.vboard.core.dict

import com.sun.management.ThreadMXBean
import ru.tinyops.android.vboard.core.bundledDictionary
import ru.tinyops.android.vboard.core.bundledImage
import ru.tinyops.android.vboard.core.gesture.GestureEngine
import ru.tinyops.android.vboard.core.gesture.GestureLayoutFactory
import ru.tinyops.android.vboard.core.layout.Language
import ru.tinyops.android.vboard.core.phoneGeometry
import ru.tinyops.android.vboard.core.swipe
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DictionaryImageTest {
    private val source = "hello\t100\nhelp\t40\nhell\t20\nhe\t90\nworld\t80\nпривет\t70".byteInputStream()

    private fun imageOf(dictionary: Dictionary): ByteArray =
        ByteArrayOutputStream().also { DictionaryImage.write(dictionary, it) }.toByteArray()

    @Test
    fun `an image answers like the parsed dictionary`() {
        val parsed = Dictionary.parse(source)
        val read = Dictionary.read(ByteBuffer.wrap(imageOf(parsed)))

        assertEquals(parsed.size, read.size)
        assertEquals(100.0, read.frequency("hello"))
        assertTrue(read.contains("привет"))
        assertFalse(read.contains("hel"))
        assertEquals(parsed.completions("he", 3), read.completions("he", 3))
        assertEquals(parsed.completions("при", 3), read.completions("при", 3))
        assertEquals(parsed.languageModel.unknownLogProb, read.languageModel.unknownLogProb)
        assertEquals(parsed.languageModel.unigramLogProb("help"), read.languageModel.unigramLogProb("help"))
        assertEquals(parsed.languageModel.vocabularySize, read.languageModel.vocabularySize)
        assertEquals(parsed.languageModel.trie.nodeCount(), read.languageModel.trie.nodeCount())
    }

    @Test
    fun `an image is read the same from a direct unaligned buffer`() {
        val image = imageOf(Dictionary.parse(source))
        val direct = ByteBuffer.allocateDirect(image.size + 1)
        direct.position(1)
        direct.put(image)
        direct.position(1)

        val read = Dictionary.read(direct.slice())

        assertEquals(listOf("hello", "he", "help"), read.completions("he", 3))
    }

    @Test
    fun `reading leaves the buffer untouched`() {
        val buffer = ByteBuffer.wrap(imageOf(Dictionary.parse(source)))
        Dictionary.read(buffer)
        assertEquals(0, buffer.position())
    }

    @Test
    fun `a foreign or truncated file is rejected`() {
        val image = imageOf(Dictionary.parse(source))
        assertFailsWith<IllegalArgumentException> { Dictionary.read(ByteBuffer.wrap(ByteArray(image.size))) }
        assertFailsWith<IllegalArgumentException> { Dictionary.read(ByteBuffer.wrap(image.copyOf(image.size - 1))) }
        assertFailsWith<IllegalArgumentException> { Dictionary.read(ByteBuffer.wrap(ByteArray(2))) }
    }

    @Test
    fun `gestures decode identically from the bundled image and from its source`() {
        for (language in Language.entries) {
            val parsed = javaClass.classLoader.getResourceAsStream("dict/${language.code}.tsv")
            val source = requireNotNull(parsed) { "missing source of ${language.code}" }.use(Dictionary::parse)
            val geometry = phoneGeometry(language)
            val layout = GestureLayoutFactory.create(geometry, xdpi = 420f, ydpi = 420f)
            val fromSource = GestureEngine().apply { setLayout(layout) }
            fromSource.setLanguageModel(source.languageModel)
            val fromImage = GestureEngine().apply { setLayout(layout) }
            fromImage.setLanguageModel(bundledDictionary(language).languageModel)
            val words = if (language == Language.RUSSIAN) listOf("привет", "клавиатуру") else listOf("hello", "people")
            for (word in words) {
                val gesture = swipe(geometry, word)
                assertEquals(
                    fromSource.decode(gesture, "", complete = true),
                    fromImage.decode(gesture, "", complete = true),
                )
            }
        }
    }

    @Test
    fun `reading an image creates no object per word`() {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val image = ByteBuffer.wrap(bundledImage(Language.RUSSIAN))
        val thread = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(thread)
        val dictionary = Dictionary.read(image)
        dictionary.languageModel
        val allocated = threads.getThreadAllocatedBytes(thread) - before
        assertTrue(allocated <= image.capacity(), "reading allocated $allocated bytes, image is ${image.capacity()}")
    }
}
