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
import ru.tinyops.android.vboard.core.dict.Dictionary
import ru.tinyops.android.vboard.core.layout.Language
import java.io.IOException

/** Loading the dictionaries and handing the results of the background thread to the main one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GestureDecoderTest {
    private val opened = ArrayList<Language>()
    private var bigramsBroken = false

    private val decoder =
        GestureDecoder(
            openDictionary = { language ->
                synchronized(opened) { opened += language }
                Dictionary.parse("hello\t10\nhelp\t5\nworld\t3\n".byteInputStream())
            },
            openBigrams = { if (bigramsBroken) throw IOException("truncated table") else null },
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
}
