package ru.tinyops.android.vboard

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import ru.tinyops.android.vboard.core.dict.BigramTable
import ru.tinyops.android.vboard.core.dict.Dictionary
import ru.tinyops.android.vboard.core.layout.Language
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/** Loading the dictionaries and handing the results of the background thread to the main one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GestureDecoderTest {
    private val opened = ArrayList<Language>()
    private var bigramsBroken = false
    private var bigramsWithPair = false

    @Volatile private var bigramsAvailable = false
    private val bigramsOpened = AtomicInteger()

    private val decoder =
        GestureDecoder(
            openDictionary = { language ->
                synchronized(opened) { opened += language }
                Dictionary.parse("hello\t10\nhelp\t5\nworld\t3\nдом\t9\nдома\t1\nмы\t1\n".byteInputStream())
            },
            openBigrams = { language ->
                if (language == Language.RUSSIAN) bigramsOpened.incrementAndGet()
                when {
                    bigramsBroken -> throw IOException("truncated table")
                    bigramsAvailable && language == Language.RUSSIAN && bigramsWithPair -> pairTable()
                    bigramsAvailable && language == Language.RUSSIAN -> emptyBigramTable()
                    else -> null
                }
            },
            bigramsAvailable = { language -> bigramsAvailable && language == Language.RUSSIAN },
        )

    @After
    fun tearDown() = decoder.shutdown()

    /** Runs the main looper until [done] or a timeout. */
    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        assertTrue("the decoder did not answer", done())
    }

    @Test
    fun `completions come once the dictionary is loaded, on the main thread`() {
        var loadedOnMain = false
        assertTrue(decoder.completions(Language.ENGLISH, "hel", 3).isEmpty())

        decoder.load(Language.ENGLISH) { loadedOnMain = Looper.myLooper() == Looper.getMainLooper() }
        await { loadedOnMain }

        assertTrue(decoder.isReady(Language.ENGLISH))
        assertEquals(listOf("hello", "help"), decoder.completions(Language.ENGLISH, "hel", 3))
    }

    @Test
    fun `a language is loaded only once`() {
        var calls = 0
        decoder.load(Language.ENGLISH) { calls++ }
        decoder.load(Language.ENGLISH) { calls++ }
        await { calls > 0 }
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        assertEquals(1, calls)
        assertEquals(listOf(Language.ENGLISH), synchronized(opened) { opened.toList() })
    }

    @Test
    fun `a broken word pair table leaves typing working`() {
        bigramsBroken = true
        var loaded = false

        decoder.load(Language.RUSSIAN) { loaded = true }
        await { loaded }

        assertEquals(listOf("hello"), decoder.completions(Language.RUSSIAN, "hell", 3))
    }

    @Test
    fun `the word pair table gives the next words and weighs completions`() {
        bigramsAvailable = true
        bigramsWithPair = true
        assertTrue(decoder.nextWords(Language.RUSSIAN, "мы ", 3).isEmpty())

        decoder.load(Language.RUSSIAN)
        await { decoder.nextWords(Language.RUSSIAN, "Мы ", 3).isNotEmpty() }

        assertEquals(listOf("дома"), decoder.nextWords(Language.RUSSIAN, "Мы ", 3))
        assertTrue(decoder.nextWords(Language.RUSSIAN, "Мы. ", 3).isEmpty())
        assertEquals(listOf("дома", "дом"), decoder.completions(Language.RUSSIAN, "до", 3, "мы "))
        assertEquals(listOf("дом", "дома"), decoder.completions(Language.RUSSIAN, "до", 3))

        bigramsAvailable = false
        decoder.refreshBigrams()
        await { decoder.nextWords(Language.RUSSIAN, "мы ", 3).isEmpty() }
    }

    @Test
    fun `a gesture result arrives on the main thread`() {
        var result: List<String>? = null
        var onMain = false
        decoder.load(Language.ENGLISH)

        decoder.decode(Language.ENGLISH, emptyList(), "", complete = true) {
            onMain = Looper.myLooper() == Looper.getMainLooper()
            result = it
        }
        await { result != null }

        assertTrue(onMain)
    }

    @Test
    fun `a table that appears later is picked up once`() {
        var loaded = false
        decoder.load(Language.RUSSIAN) { loaded = true }
        await { loaded }
        await { bigramsOpened.get() == 1 }

        bigramsAvailable = true
        decoder.refreshBigrams()
        decoder.refreshBigrams()
        await { bigramsOpened.get() >= 2 }
        Thread.sleep(50)

        assertEquals(2, bigramsOpened.get())
    }

    @Test
    fun `a table that is gone is let go and opened again when it returns`() {
        bigramsAvailable = true
        var loaded = false
        decoder.load(Language.RUSSIAN) { loaded = true }
        await { loaded }
        await { bigramsOpened.get() == 1 }

        bigramsAvailable = false
        decoder.refreshBigrams()
        // The decoder runs its tasks in order: once English is loaded, the refresh above has run.
        var drained = false
        decoder.load(Language.ENGLISH) { drained = true }
        await { drained }
        assertEquals(1, bigramsOpened.get())

        bigramsAvailable = true
        decoder.refreshBigrams()
        await { bigramsOpened.get() == 2 }
    }
}

/** A table without words: a header and two empty offset arrays. */
private fun emptyBigramTable(): BigramTable =
    BigramTable.read(
        ByteBuffer
            .allocate(32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x4B424247)
            .putInt(1)
            .rewind() as ByteBuffer,
    )

/** "мы дома" seen 1000 times: two words, `дома` first in byte order, and one pair. */
private fun pairTable(): BigramTable {
    val text = "домамы".toByteArray(Charsets.UTF_8)
    val buffer = ByteBuffer.allocate(24 + 12 + text.size + 8 + 8 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
    buffer
        .putInt(0x4B424247)
        .putInt(1)
        .putInt(2)
        .putInt(1)
        .putLong(1000)
    buffer.putInt(0).putInt(8).putInt(text.size)
    buffer.put(text)
    buffer.putFloat(-20f).putFloat(-5f)
    buffer.putInt(0).putInt(1000)
    buffer.putInt(0).putInt(0).putInt(1)
    buffer.putInt(Math.round(Math.log(1000.0) * 256).toInt())
    return BigramTable.read(buffer.rewind() as ByteBuffer)
}
